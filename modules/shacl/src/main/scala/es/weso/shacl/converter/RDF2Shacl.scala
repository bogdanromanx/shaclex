package es.weso.shacl.converter

import cats._
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import es.weso.rdf.PREFIXES._
import es.weso.rdf.RDFReader
import es.weso.rdf.nodes._
import es.weso.rdf.parser.RDFParser
import es.weso.rdf.path._
import es.weso.shacl.SHACLPrefixes._
import es.weso.shacl._
import es.weso.utils.TryUtils._

import scala.util.{ Failure, Success, Try }

class RDF2Shacl extends RDFParser with LazyLogging {

  // Keep track of parsed shapes
  // TODO: Refactor this code to use a StateT
  val parsedShapes = collection.mutable.Map[ShapeRef, Shape]()

  // TODO: Refactor this code to avoid imperative style
  var pendingNodes = List[RDFNode]()

  def tryGetShacl(rdf: RDFReader): Try[Schema] =
    getShacl(rdf).fold(
      str => Failure(new Exception(str)),
      Success(_))

  /**
   * Parses RDF content and obtains a SHACL Schema and a PrefixMap
   */
  def getShacl(rdf: RDFReader): Either[String, Schema] = {
    val pm = rdf.getPrefixMap
    for {
      shapesMap <- shapesMap(rdf)
    } yield (Schema(pm, shapesMap))
  }

  type ShapesMap = Map[ShapeRef, Shape]

  def shapesMap(rdf: RDFReader): Either[String, ShapesMap] = {
    parsedShapes.clear()
    val nodeShapes = subjectsWithType(sh_NodeShape, rdf)
    val propertyShapes = subjectsWithType(sh_PropertyShape, rdf)
    val shapes = subjectsWithType(sh_Shape, rdf)
    val allShapes: Set[RDFNode] = nodeShapes ++ propertyShapes ++ shapes
    pendingNodes = allShapes.toList
    parseShapes(rdf)
  }

  def parseShapes(rdf: RDFReader): Either[String, ShapesMap] = {
    pendingNodes.size match {
      case 0 => Right(parsedShapes.toMap)
      case _ => {
        val nodes = pendingNodes
        pendingNodes = List() // Cleans list of pending nodes...
        logger.info(s"parseShapes: Nodes: ${nodes.mkString(",")}. Pending nodes: ${pendingNodes.mkString(",")}")
        parseNodes(nodes, shape)(rdf) match {
          case Left(s) => Left(s)
          case Right(vs) => // Continue parsing in case pendingNodes was filled with some values during parsing
            parseShapes(rdf)
        }
      }
    }
  }

  type ShaclParser[A] = Set[RDFNode] => RDFParser[(A, Set[RDFNode])]

  def shape: RDFParser[ShapeRef] = (n, rdf) => {
    val shapeRef = ShapeRef(n)
    if (parsedShapes.contains(shapeRef)) {
      parseOk(shapeRef)
    } else {
      for {
        shapeRef <- firstOf(nodeShape, propertyShape)(n, rdf)
      } yield {
        //        parsedShapes(shapeRef) = newShape
        shapeRef
      }
    }
  }

  def mkId(n: RDFNode): Option[IRI] = n match {
    case iri: IRI => Some(iri)
    case _ => None
  }

  def nodeShape: RDFParser[ShapeRef] = (n, rdf) => for {
    types <- rdfTypes(n, rdf)
    _ <- failIf(types.contains(sh_PropertyShape), "Node shapes must not have rdf:type sh:PropertyShape")(n, rdf)
    targets <- targets(n, rdf)
    propertyShapes <- propertyShapes(n, rdf)
    components <- components(n, rdf)
    closed <- booleanFromPredicateOptional(sh_closed)(n, rdf)
    ignoredNodes <- rdfListForPredicateOptional(sh_ignoredProperties)(n, rdf)
    ignoredIRIs <- nodes2iris(ignoredNodes)
  } yield {
    val shape: Shape = NodeShape(
      id = n,
      components = components.toList,
      targets = targets,
      propertyShapes = propertyShapes,
      closed = closed.getOrElse(false),
      ignoredProperties = ignoredIRIs)
    val sref = ShapeRef(n)
    parsedShapes += (sref -> shape)
    sref
  }

  def propertyShape: RDFParser[ShapeRef] = (n, rdf) => for {
    types <- rdfTypes(n, rdf)
    _ <- failIf(types.contains(sh_NodeShape), "Property shapes must not have rdf:type sh:NodeShape")(n, rdf)
    targets <- targets(n, rdf)
    nodePath <- objectFromPredicate(sh_path)(n, rdf)
    path <- parsePath(nodePath, rdf)
    propertyShapes <- propertyShapes(n, rdf)
    components <- components(n, rdf)
    closed <- booleanFromPredicateOptional(sh_closed)(n, rdf)
    ignoredNodes <- rdfListForPredicateOptional(sh_ignoredProperties)(n, rdf)
    ignoredIRIs <- nodes2iris(ignoredNodes)
  } yield {
    val ps = PropertyShape(
      id = n,
      path = path,
      components = components.toList,
      targets = targets,
      propertyShapes = propertyShapes,
      closed = closed.getOrElse(false),
      ignoredProperties = ignoredIRIs)
    val sref = ShapeRef(n)
    parsedShapes += (sref -> ps)
    sref
  }

  def targets: RDFParser[Seq[Target]] =
    combineAll(
      targetNodes,
      targetClasses,
      implicitTargetClass,
      targetSubjectsOf,
      targetObjectsOf)

  def targetNodes: RDFParser[Seq[Target]] = (n, rdf) => {
    for {
      ns <- objectsFromPredicate(sh_targetNode)(n, rdf)
      vs <- sequenceEither(ns.toList.map(mkTargetNode))
    } yield vs
  }

  def targetClasses: RDFParser[Seq[Target]] = (n, rdf) =>
    for {
      ns <- objectsFromPredicate(sh_targetClass)(n, rdf)
      vs <- sequenceEither(ns.toList.map(mkTargetClass))
    } yield vs

  def implicitTargetClass: RDFParser[Seq[Target]] = (n, rdf) => {
    val shapeTypes = rdf.triplesWithSubjectPredicate(n, rdf_type).map(_.obj)
    val rdfs_Class = rdfs + "Class"
    if (shapeTypes.contains(rdfs_Class))
      mkTargetClass(n).map(Seq(_))
    else
      Right(Seq())
  }

  def targetSubjectsOf: RDFParser[Seq[Target]] = (n, rdf) => {
    for {
      ns <- objectsFromPredicate(sh_targetSubjectsOf)(n, rdf)
      vs <- sequenceEither(ns.toList.map(mkTargetSubjectsOf))
    } yield vs
  }

  def targetObjectsOf: RDFParser[Seq[Target]] = (n, rdf) => {
    for {
      ns <- objectsFromPredicate(sh_targetObjectsOf)(n, rdf)
      vs <- sequenceEither(ns.toList.map(mkTargetObjectsOf))
    } yield vs
  }

  def mkTargetNode(n: RDFNode): Either[String, TargetNode] = parseOk(TargetNode(n))

  def mkTargetClass(n: RDFNode): Either[String, TargetClass] = parseOk(TargetClass(n))

  def mkTargetSubjectsOf(n: RDFNode): Either[String, TargetSubjectsOf] = n match {
    case i: IRI => parseOk(TargetSubjectsOf(i))
    case _ => parseFail(s"targetSubjectsOf requires an IRI. Obtained $n")
  }

  def mkTargetObjectsOf(n: RDFNode): Either[String, TargetObjectsOf] = n match {
    case i: IRI => parseOk(TargetObjectsOf(i))
    case _ => parseFail(s"targetObjectsOf requires an IRI. Obtained $n")
  }

  /*  def propertyShapes: RDFParser[Seq[PropertyShape]] = (n,rdf) =>
    if (!isPropertyShape(n,rdf)) {
      ??? // combineAll(propertyConstraints, nodeConstraints)(n,rdf)
  } else for {
    p <- propertyShape(n,rdf)
  } yield Seq(p) */

  def isPropertyShape(node: RDFNode, rdf: RDFReader): Boolean = {
    rdf.getTypes(node).contains(sh_PropertyShape) ||
      !rdf.triplesWithSubjectPredicate(node, sh_path).isEmpty
  }

  /*  def nodeShapes: RDFParser[Seq[NodeShape]] = (n, rdf) => {
   val id = if (n.isIRI) Some(n.toIRI) else None
   for {
     cs <- components(n,rdf)
   } yield cs.map(c => NodeShape(id, components = List(c)))
  } */

  def propertyShapes: RDFParser[Seq[ShapeRef]] = (n, rdf) => {
    for {
      ps <- objectsFromPredicate(sh_property)(n, rdf)
      vs <- sequenceEither(ps.toList.map(p => propertyShapeRef(p, rdf)))
    } yield vs
  }

  def propertyShapeRef: RDFParser[ShapeRef] = (n, rdf) => {
    pendingNodes = n :: pendingNodes
    parseOk(ShapeRef(n))
  }

  /*
  def propertyShape: RDFParser[PropertyShape] = (n, rdf) => {
    val id = if (n.isIRI) Some(n.toIRI) else None
    for {
      nodePath <- objectFromPredicate(sh_path)(n,rdf)
      path <- parsePath(nodePath,rdf)
      components <- components(n,rdf)
    } yield {
      PropertyShape(id, path, components)
    }
  } */

  def parsePath: RDFParser[SHACLPath] = (n, rdf) => {
    n match {
      case iri: IRI => Right(PredicatePath(iri))
      case bnode: BNodeId => someOf(
        inversePath,
        oneOrMorePath,
        zeroOrMorePath,
        zeroOrOnePath,
        alternativePath,
        sequencePath)(n, rdf)
      case _ => parseFail(s"Unsupported value $n for path")
    }
  }

  def inversePath: RDFParser[SHACLPath] = (n, rdf) => for {
    pathNode <- objectFromPredicate(sh_inversePath)(n, rdf)
    path <- parsePath(pathNode, rdf)
  } yield InversePath(path)

  def oneOrMorePath: RDFParser[SHACLPath] = (n, rdf) => for {
    pathNode <- objectFromPredicate(sh_oneOrMorePath)(n, rdf)
    path <- parsePath(pathNode, rdf)
  } yield OneOrMorePath(path)

  def zeroOrMorePath: RDFParser[SHACLPath] = (n, rdf) => for {
    pathNode <- objectFromPredicate(sh_zeroOrMorePath)(n, rdf)
    path <- parsePath(pathNode, rdf)
  } yield ZeroOrMorePath(path)

  def zeroOrOnePath: RDFParser[SHACLPath] = (n, rdf) => for {
    pathNode <- objectFromPredicate(sh_zeroOrOnePath)(n, rdf)
    path <- parsePath(pathNode, rdf)
  } yield ZeroOrOnePath(path)

  def alternativePath: RDFParser[SHACLPath] = (n, rdf) => for {
    pathNode <- objectFromPredicate(sh_alternativePath)(n, rdf)
    pathNodes <- rdfList(pathNode, rdf)
    paths <- group(parsePath, pathNodes)(n, rdf)
  } yield AlternativePath(paths)

  def sequencePath: RDFParser[SHACLPath] = (n, rdf) => for {
    pathNodes <- rdfList(n, rdf)
    paths <- group(parsePath, pathNodes)(n, rdf)
  } yield {
    SequencePath(paths)
  }

  def components: RDFParser[Seq[Component]] =
    anyOf(
      classComponent,
      datatype,
      nodeKind,
      minCount, maxCount,
      minExclusive, maxExclusive, minInclusive, maxInclusive,
      minLength, maxLength,
      pattern, languageIn, uniqueLang,
      equals, disjoint, lessThan, lessThanOrEquals,
      or, and, not, xone, qualifiedValueShape,
      nodeComponent,
      hasValue,
      in)

  def classComponent = parsePredicate(sh_class, ClassComponent)

  def datatype = parsePredicateIRI(sh_datatype, Datatype)

  def minInclusive = parsePredicateLiteral(sh_minInclusive, MinInclusive)

  def maxInclusive = parsePredicateLiteral(sh_maxInclusive, MaxInclusive)

  def minExclusive = parsePredicateLiteral(sh_minExclusive, MinExclusive)

  def maxExclusive = parsePredicateLiteral(sh_maxExclusive, MaxExclusive)

  def minLength = parsePredicateInt(sh_minLength, MinLength)

  def maxLength = parsePredicateInt(sh_maxLength, MaxLength)

  def pattern: RDFParser[Pattern] = (n, rdf) => for {
    pat <- stringFromPredicate(sh_pattern)(n, rdf)
    flags <- stringFromPredicateOptional(sh_flags)(n, rdf)
  } yield Pattern(pat, flags)

  def languageIn: RDFParser[LanguageIn] = (n, rdf) => for {
    rs <- rdfListForPredicate(sh_languageIn)(n, rdf)
    ls <- sequenceEither(rs.map(n => n match {
      case StringLiteral(str) => parseOk(str)
      case _ => parseFail(s"Expected to be a string literal but got $n")
    }))
  } yield LanguageIn(ls)

  def uniqueLang: RDFParser[UniqueLang] = (n, rdf) => for {
    b <- booleanFromPredicate(sh_uniqueLang)(n, rdf)
  } yield UniqueLang(b)

  def equals = parsePredicateComparison(sh_equals, Equals)

  def disjoint = parsePredicateComparison(sh_disjoint, Disjoint)

  def lessThan = parsePredicateComparison(sh_lessThan, LessThan)

  def lessThanOrEquals = parsePredicateComparison(sh_lessThanOrEquals, LessThanOrEquals)

  def parsePredicateComparison(pred: IRI, mkComp: IRI => Component): RDFParser[Component] = (n, rdf) => for {
    p <- iriFromPredicate(pred)(n, rdf)
  } yield mkComp(p)

  def or: RDFParser[Or] = (n, rdf) => for {
    shapeNodes <- rdfListForPredicate(sh_or)(n, rdf)
    shapes <- mapRDFParser(shapeNodes.toList, shapeRefConst)(n, rdf)
  } yield Or(shapes)

  def and: RDFParser[And] = (n, rdf) => for {
    nodes <- rdfListForPredicate(sh_and)(n, rdf)
    shapes <- mapRDFParser(nodes, shapeRefConst)(n, rdf)
  } yield And(shapes)

  def xone: RDFParser[Xone] = (n, rdf) => for {
    nodes <- rdfListForPredicate(sh_xone)(n, rdf)
    shapes <- mapRDFParser(nodes, shapeRefConst)(n, rdf)
  } yield Xone(shapes)

  // TODO: Check if this must take into account that not is optional...
  def not: RDFParser[Not] = (n, rdf) => for {
    shapeNode <- objectFromPredicate(sh_not)(n, rdf)
    sref <- shapeRef(shapeNode, rdf)
  } yield Not(sref)

  def nodeComponent: RDFParser[NodeComponent] = (n, rdf) => {
    for {
      nodeShape <- objectFromPredicate(sh_node)(n, rdf)
      sref <- shapeRef(nodeShape, rdf)
    } yield {
      NodeComponent(sref)
    }
  }

  def qualifiedValueShape: RDFParser[QualifiedValueShape] = (n, rdf) => for {
    obj <- objectFromPredicate(sh_qualifiedValueShape)(n, rdf)
    sref <- shapeRef(obj, rdf)
    min <- optional(integerLiteralForPredicate(sh_qualifiedMinCount))(n, rdf)
    max <- optional(integerLiteralForPredicate(sh_qualifiedMaxCount))(n, rdf)
    disjoint <- booleanFromPredicateOptional(sh_qualifiedValueShapesDisjoint)(n, rdf)
  } yield QualifiedValueShape(sref, min, max, disjoint)

  def shapeRef: RDFParser[ShapeRef] = (n, rdf) => {
    pendingNodes = n :: pendingNodes
    parseOk(ShapeRef(n))
  }

  def shapeRefConst(sref: RDFNode): RDFParser[ShapeRef] = (_, rdf) =>
    shapeRef(sref, rdf)

  def minCount = parsePredicateInt(sh_minCount, MinCount)
  def maxCount = parsePredicateInt(sh_maxCount, MaxCount)

  def hasValue: RDFParser[Component] = (n, rdf) => {
    logger.info(s"Parsing hasValue on $n")
    for {
      o <- objectFromPredicate(sh_hasValue)(n, rdf)
      v <- {
        logger.info(s"Object of hasValue $n = $o")
        node2Value(o)
      }
    } yield {
      logger.info(s"Value parsed: $v")
      HasValue(v)
    }
  }

  def in: RDFParser[Component] = (n, rdf) => {
    for {
      ns <- rdfListForPredicate(sh_in)(n, rdf)
      vs <- convert2Values(ns.map(node2Value(_)))
    } yield In(vs)
  }

  def node2Value(n: RDFNode): Either[String, Value] = {
    n match {
      case i: IRI => parseOk(IRIValue(i))
      case l: Literal => parseOk(LiteralValue(l))
      case _ => parseFail(s"Element $n must be a IRI or a Literal to be part of sh:in")
    }
  }

  def convert2Values[A](cs: List[Either[String, A]]): Either[String, List[A]] = {
    if (cs.isEmpty)
      parseFail("The list of values associated with sh:in must not be empty")
    else {
      sequenceEither(cs)
    }
  }

  def nodeKind: RDFParser[Component] = (n, rdf) => {
    for {
      os <- objectsFromPredicate(sh_nodeKind)(n, rdf)
      nk <- parseNodeKind(os)
    } yield {
      nk
    }
  }

  def parseNodeKind(os: Set[RDFNode]): Either[String, Component] = {
    os.size match {
      case 0 => parseFail("no iriObjects of nodeKind property")
      case 1 => {
        os.head match {
          case nk: IRI => nk match {
            case `sh_IRI` => parseOk(NodeKind(IRIKind))
            case `sh_BlankNode` => parseOk(NodeKind(BlankNodeKind))
            case `sh_Literal` => parseOk(NodeKind(LiteralKind))
            case `sh_BlankNodeOrLiteral` => parseOk(NodeKind(BlankNodeOrLiteral))
            case `sh_BlankNodeOrIRI` => parseOk(NodeKind(BlankNodeOrIRI))
            case `sh_IRIOrLiteral` => parseOk(NodeKind(IRIOrLiteral))
            case x => {
              logger.error(s"incorrect value of nodeKind property $x")
              parseFail(s"incorrect value of nodeKind property $x")
            }
          }
          case x => {
            logger.error(s"incorrect value of nodeKind property $x")
            parseFail(s"incorrect value of nodeKind property $x")
          }
        }
      }
      case n => parseFail(s"iriObjects of nodeKind property > 1. $os")
    }
  }

  def parsePredicateLiteral[A](p: IRI, maker: Literal => A): RDFParser[A] = (n, rdf) => for {
    v <- literalFromPredicate(p)(n, rdf)
  } yield maker(v)

  def parsePredicateInt[A](p: IRI, maker: Int => A): RDFParser[A] = (n, rdf) => for {
    v <- integerLiteralForPredicate(p)(n, rdf)
  } yield maker(v.intValue())

  def parsePredicateString[A](p: IRI, maker: String => A): RDFParser[A] = (n, rdf) => for {
    v <- stringFromPredicate(p)(n, rdf)
  } yield maker(v)

  def parsePredicate[A](p: IRI, maker: RDFNode => A): RDFParser[A] = (n, rdf) => for {
    o <- objectFromPredicate(p)(n, rdf)
  } yield maker(o)

  def parsePredicateIRI[A](p: IRI, maker: IRI => A): RDFParser[A] = (n, rdf) => for {
    iri <- iriFromPredicate(p)(n, rdf)
  } yield maker(iri)

  def noTarget: Seq[Target] = Seq()
  def noPropertyShapes: Seq[PropertyShape] = Seq()

}

object RDF2Shacl {
  final def getShacl(rdf: RDFReader): Either[String, Schema] =
    new RDF2Shacl().getShacl(rdf)

  final def tryGetShacl(rdf: RDFReader): Try[Schema] =
    new RDF2Shacl().tryGetShacl(rdf)
}