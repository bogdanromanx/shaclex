package es.weso.shex.shexR
import com.typesafe.scalalogging.LazyLogging
import es.weso.rdf.RDFReader
import es.weso.rdf.parser.RDFParser
import es.weso.shex._
import es.weso.rdf.PREFIXES._
import es.weso.shex.shexR.PREFIXES._
import cats._
import cats.implicits._
import es.weso.rdf.nodes._

import scala.util.{Failure, Success, Try}

/* Parses RDF into SHEx.
 * The parser follows ShExR definition: https://github.com/shexSpec/shexTest/blob/master/doc/ShExR.shex
 */
trait RDF2ShEx extends RDFParser with LazyLogging {

  def getSchemas (rdf: RDFReader): Try[List[Schema]] = {
    val schemaNodes = rdf.triplesWithPredicateObject(rdf_type, sx_Schema).map(_.subj).toList
    schemaNodes.map(schema(_,rdf)).sequence
  }

  def schema: RDFParser[Schema] = (n,rdf) => for {
    _ <- checkType(sx_Schema)(n,rdf)
    startActions <- opt(sx_startActs, semActList1Plus)(n,rdf)
    start <- opt(sx_start, shapeExpr)(n,rdf)
    shapePairs <- starWithNodes(sx_shapes, shapeExpr)(n,rdf)
    shapeMap <- cnvShapePairs(shapePairs)
  } yield {
    val shapes = if (shapeMap.isEmpty) None
                 else Some(shapeMap)
    Schema(Some(rdf.getPrefixMap()),None,startActions,start,shapes)
  }

  def cnvShapePairs(ps: List[(RDFNode,ShapeExpr)]): Try[Map[ShapeLabel,ShapeExpr]] = {
    ps.map(cnvShapePair).sequence.map(_.toMap)
  }

  def cnvShapePair(p: (RDFNode,ShapeExpr)): Try[(ShapeLabel,ShapeExpr)] =
    toLabel(p._1).map(l => (l,p._2))

  def toLabel(node: RDFNode): Try[ShapeLabel] = node match {
    case i: IRI => Success(IRILabel(i))
    case b: BNodeId => Success(BNodeLabel(b))
    case _ => parseFail(s"node $node must be an IRI or a BNode in order to be a ShapeLabel")
  }

  def shapeExpr: RDFParser[ShapeExpr] = firstOf(
    shapeOr,
    shapeAnd,
    shapeNot,
    nodeConstraint,
    shape,
    shapeExternal
  )

  def shapeOr: RDFParser[ShapeOr] = (n,rdf) => for {
    _ <- checkType(sx_ShapeOr)(n,rdf)
    shapeExprs <- arc(sx_shapeExprs, shapeExprList2Plus)(n,rdf)
  } yield ShapeOr(shapeExprs)

  def shapeAnd: RDFParser[ShapeAnd] = (n,rdf) => for {
    _ <- checkType(sx_ShapeAnd)(n,rdf)
    shapeExprs <- arc(sx_shapeExprs, shapeExprList2Plus)(n,rdf)
  } yield ShapeAnd(shapeExprs)

  def shapeNot: RDFParser[ShapeNot] = (n,rdf) => for {
    _ <- checkType(sx_ShapeNot)(n,rdf)
    shapeExpr <- arc(sx_shapeExpr, shapeExpr)(n,rdf)
  } yield ShapeNot(shapeExpr)

  def nodeConstraint: RDFParser[NodeConstraint] = (n,rdf) => for {
    _ <- checkType(sx_NodeConstraint)(n,rdf)
    nk <- opt(sx_nodeKind,nodeKind)(n,rdf)
  } yield NodeConstraint(nk,None,List(),None)

  def nodeKind: RDFParser[NodeKind] = (n,rdf) => n match {
    case `sx_iri` => Success(IRIKind)
    case `sx_bnode` => Success(BNodeKind)
    case `sx_literal` => Success(LiteralKind)
    case `sx_nonliteral` => Success(NonLiteralKind)
    case _ => parseFail(s"Expected nodekind, found: $n")
  }

  def shape: RDFParser[Shape] = (n,rdf) => for {
    _ <- checkType(sx_Shape)(n,rdf)
    closed <- opt(sx_closed, booleanLiteral)(n,rdf)
    extras <- star(sx_extra, iri)(n,rdf)
    // TODO
  } yield Shape(None,closed, if (extras.isEmpty) None else Some(extras), None, None,None)

  def booleanLiteral : RDFParser[Boolean] = (n,rdf) => n match {
    case BooleanLiteral.trueLiteral => Success(true)
    case BooleanLiteral.falseLiteral => Success(false)
    case DatatypeLiteral("true",xsd_boolean) => Success(true)
    case DatatypeLiteral("false",xsd_boolean) => Success(false)
    case _ => parseFail(s"Expected boolean literal. Found $n")
  }

  def iri: RDFParser[IRI] = (n,rdf) => n match {
    case i: IRI => Success(i)
    case _ => parseFail(s"Expected IRI, found $n")
  }

  def shapeExternal: RDFParser[ShapeExternal] = (n,rdf) => for {
    _ <- checkType(sx_ShapeExternal)(n,rdf)
  } yield ShapeExternal()

  def semAct: RDFParser[SemAct] = (n,rdf) => for {
    _ <- checkType(sx_SemAct)(n,rdf)
    name <- iriFromPredicate(sx_name)(n,rdf)
    code <- optional(stringFromPredicate(sx_code))(n,rdf)
  } yield SemAct(name,code)

  def tripleExpression: RDFParser[TripleExpr] =
    someOf(tripleConstraint, someOf, eachOf)

  def tripleConstraint: RDFParser[TripleConstraint] = ???
  def someOf: RDFParser[SomeOf] = ???
  def eachOf: RDFParser[EachOf] = ???

  def tripleExpressionList2Plus : RDFParser[List[TripleExpr]] = list2Plus(tripleExpression)

  def semActList1Plus: RDFParser[List[SemAct]] = list1Plus(semAct)

  def shapeExprList2Plus: RDFParser[List[ShapeExpr]] = list2Plus(shapeExpr)

  def shapeExprList1Plus: RDFParser[List[ShapeExpr]] =
    list1Plus(shapeExpr)

    /*(n,rdf) => for {
    se1 <- arc(rdf_first,shapeExpr)
    ses <- arc(rdf_rest,shapeExprList1Plus)
    Success(se1 +: ses)
  }*/




}