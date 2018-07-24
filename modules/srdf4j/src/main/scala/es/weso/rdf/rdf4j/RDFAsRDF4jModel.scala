package es.weso.rdf.rdf4j

import java.io._

import es.weso.rdf._
import es.weso.rdf.path.SHACLPath
import es.weso.rdf.triples.RDFTriple
import io.circe.Json
import org.eclipse.rdf4j.model.{IRI => IRI_RDF4j, BNode => _, Literal => _, _}
import es.weso.rdf.nodes._
import org.eclipse.rdf4j.model.util.{ModelBuilder, Models}
import org.eclipse.rdf4j.rio.RDFFormat._
import org.eclipse.rdf4j.rio.{RDFFormat, Rio}
import org.apache.commons.io.input.CharSequenceInputStream

import scala.util._
import scala.collection.JavaConverters._
import RDF4jMapper._
import cats.implicits._

case class RDFAsRDF4jModel(model: Model)
  extends RDFReader
    with RDFBuilder
    with RDFReasoner {

  type Rdf = RDFAsRDF4jModel

  override def availableParseFormats: List[String] = RDFAsRDF4jModel.availableFormats
  override def availableSerializeFormats: List[String] = RDFAsRDF4jModel.availableFormats

  override def fromString(cs: CharSequence,
                          format: String,
                          base: Option[String] = None): Either[String, Rdf] = {
      // val builder = new ModelBuilder()
      val baseURI = base.getOrElse("")
      for {
        format <- getRDFFormat(format)
        model <- Try {
          val is : InputStream = new CharSequenceInputStream(cs,"UTF-8")
          Rio.parse(is, baseURI, format)
        }.fold(e => Left(s"Exception: ${e.getMessage}\nBase:$base, format: $format\n$cs"),
          Right(_)
        )
   } yield RDFAsRDF4jModel(model)
  }

  private def getRDFFormat(name: String): Either[String,RDFFormat] = {
    name.toUpperCase match {
      case "TURTLE" => Right(TURTLE)
      case "JSONLD" => Right(JSONLD)
      case "RDFXML" => Right(RDFXML)
      case x => Left(s"Unsupported syntax $x")
    }
  }

  override def serialize(formatName: String): Either[String, String] = for {
    format <- getRDFFormat(formatName)
    str <- Try {
      val out: StringWriter = new StringWriter()
      Rio.write(model,out,format)
      out.toString
    }.fold(e => Left(s"Error serializing RDF to format $formatName: $e"),
      Right(_)
    )
  } yield str

/*  private def extend_rdfs: Rdf = {
    this
    // TODO: Check how to add inference in RDF4j
    /* val infModel = ModelFactory.createRDFSModel(model)
    RDFAsJenaModel(infModel) */
  } */

  // TODO: this implementation only returns subjects
  override def iris(): Set[IRI] = {
    val resources: Set[Resource] = model.subjects().asScala.toSet
    resources.filter(_.isInstanceOf[IRI_RDF4j]).map(_.asInstanceOf[IRI_RDF4j].toString).map(IRI(_))
  }

  override def subjects(): Set[RDFNode] = {
    val resources: Set[Resource] = model.subjects().asScala.toSet
    resources.map(r => resource2RDFNode(r))
  }

  override def rdfTriples(): Set[RDFTriple] = {
    model.asScala.toSet.map(statement2RDFTriple(_))
  }

  override def triplesWithSubject(node: RDFNode): Set[RDFTriple] = {
    val maybeResource = rdfNode2Resource(node).toOption
    val empty: Set[RDFTriple] = Set()
    maybeResource.fold(empty) {
      case resource =>
        val statements: Set[Statement] = triplesSubject(resource, model)
        statements2RDFTriples(statements)
    }
  }

  /**
    * return the SHACL instances of a node `cls`
    * A node `node` is a shacl instance of `cls` if `node rdf:type/rdfs:subClassOf* cls`
    */
  override def getSHACLInstances(c: RDFNode): Seq[RDFNode] = {
    RDF4jUtils.getSHACLInstances(c,model)
  }

  override def hasSHACLClass(n: RDFNode, c: RDFNode): Boolean = {
    RDF4jUtils.getSHACLInstances(c,model) contains(n)
  }

  override def hasSHACLRootClass(n: RDFNode, c: RDFNode): Boolean = ???

  override def nodesWithPath(path: SHACLPath): Set[(RDFNode, RDFNode)] = {
    /*
    val jenaPath: Path = JenaMapper.path2JenaPath(path, model)
    val pairs = JenaUtils.getNodesFromPath(jenaPath, model).
      map(p => (JenaMapper.jenaNode2RDFNode(p._1), JenaMapper.jenaNode2RDFNode(p._2)))
    pairs.toSet */
    ???
  }

  override def objectsWithPath(subj: RDFNode, path: SHACLPath): Set[RDFNode] = {
    RDF4jUtils.objectsWithPath(subj,path,model).toSet
  }

  override def subjectsWithPath(path: SHACLPath, obj: RDFNode): Set[RDFNode] = {
    RDF4jUtils.subjectsWithPath(obj,path,model).toSet
  }

  override def triplesWithPredicate(iri: IRI): Set[RDFTriple] = {
    val pred = iri2Property(iri)
    statements2RDFTriples(triplesPredicate(pred, model))
  }

  override def triplesWithObject(node: RDFNode): Set[RDFTriple] = {
    val obj = rdfNode2Resource(node).toOption
    // val empty: Set[RDFTriple] = Set()
    obj.fold(emptySet) { o => {
      statements2RDFTriples(triplesObject(o, model))
    }
   }
  }

  private lazy val emptySet: Set[RDFTriple] = Set()

  override def triplesWithPredicateObject(p: IRI, o: RDFNode): Set[RDFTriple] = {
    val prop = iri2Property(p)
    val maybeObj = rdfNode2Resource(o).toOption
    maybeObj.fold(emptySet) { obj =>
      statements2RDFTriples(triplesPredicateObject(prop,obj, model))
    }
  }

  override def getPrefixMap: PrefixMap = {
    PrefixMap {
      val nsSet: Set[Namespace] = model.getNamespaces.asScala.toSet
      nsSet.map(ns => (Prefix(ns.getPrefix), IRI(ns.getName))).toMap
    }
  }

  override def addPrefixMap(pm: PrefixMap): Rdf = {
    pm.pm.foreach {
      case (Prefix(prefix),value) => model.setNamespace(prefix,value.str)
    }
    this
  }

  override def addTriples(triples: Set[RDFTriple]): Either[String,Rdf]  = for {
    statements <- triples.map(rdfTriple2Statement(_)).toList.sequence
  } yield {
    // val xs: List[Statement] = statements
    model.addAll(statements.asJava)
    this
  }

  // TODO: This is not efficient
  override def rmTriple(triple: RDFTriple): Either[String,Rdf] = for {
    s <- rdfTriple2Statement(triple)
  } yield {
    model.remove(s)
    this
  }

  override def createBNode: (RDFNode, Rdf) = {
    (BNode(newBNode.getID), this)
  }

  override def addPrefix(alias: String, iri: String): Rdf = {
    model.setNamespace(alias,iri)
    this
  }

  override def empty: Rdf = {
    RDFAsRDF4jModel.empty
  }

  override def checkDatatype(node: RDFNode, datatype: IRI): Either[String, Boolean] =
    wellTypedDatatype(node,datatype)

  /*private def resolveString(str: String): Either[String,IRI] = {
    Try(IRIResolver.resolveString(str)).fold(
      e => Left(e.getMessage),
      iri => Right(IRI(iri))
    )
  }*/
  private val NONE = "NONE"
  private val RDFS = "RDFS"
  private val OWL = "OWL"

  override def applyInference(inference: String): Either[String, Rdf] = {
    Right(this)  // TODO (as it is doesn't apply inference)
/*
    inference.toUpperCase match {
      case `NONE` => Right(this)
      case `RDFS` => JenaUtils.inference(model, RDFS).map(RDFAsJenaModel(_))
      case `OWL` => JenaUtils.inference(model, OWL).map(RDFAsJenaModel(_))
      case other => Left(s"Unsupported inference $other")
    }
*/
  }

  override def availableInferenceEngines: List[String] = List(NONE, RDFS, OWL)

  override def querySelect(queryStr: String): Either[String, List[Map[String,RDFNode]]] =
   Left(s"Not implemented querySelect for RDf4j yet")

  override def queryAsJson(queryStr: String): Either[String, Json] =
    Left(s"Not implemented queryAsJson for RDf4j")

  override def getNumberOfStatements(): Either[String,Int] =
    Right(model.size)

  def isIsomorphicWith(other: RDFReader): Either[String,Boolean] = other match {
    case o: RDFAsRDF4jModel => Right(Models.isomorphic(model,o.model))
    case _ => Left(s"Cannot compare RDFAsJenaModel with reader of different type: ${other.getClass.toString}")
  }

}


object RDFAsRDF4jModel {

    def apply(): RDFAsRDF4jModel = {
    RDFAsRDF4jModel.empty
  }

  lazy val empty: RDFAsRDF4jModel = {
    val builder = new ModelBuilder()
    RDFAsRDF4jModel(builder.build)
  }

  def fromChars(cs: CharSequence, format: String, base: Option[String] = None): Either[String,RDFAsRDF4jModel] = {
    RDFAsRDF4jModel.empty.fromString(cs, format, base)
  }

  def availableFormats: List[String] = {
    val formats = List(TURTLE,JSONLD,RDFXML)
    formats.map(_.getName)
  }


}
