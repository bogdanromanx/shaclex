package es.weso.rdf.jena

import org.apache.jena.query._
import es.weso.rdf.nodes._
import es.weso.rdf.nodes.RDFNode
import es.weso.rdf.triples.RDFTriple

import scala.collection.JavaConverters._
import scala.util.{Either, Left}
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Statement
import org.apache.jena.rdf.model.Model
import org.slf4j._
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.rdf.model.ModelFactory
import es.weso.rdf._
import es.weso.rdf.jena.SPARQLQueries._
import es.weso.rdf.path.SHACLPath
import io.circe.Json
import org.apache.jena.rdf.model.{RDFNode => JenaRDFNode}

case class RDFFromWeb() extends RDFReader {
  type Rdf = RDFFromWeb

  val log = LoggerFactory.getLogger("RDFFromWeb")

  def availableParseFormats: List[String] = List()
  def availableSerializeFormats: List[String] = List()

  override def getPrefixMap: PrefixMap = {
    // TODO: Can we get more info about prefix maps from an endpoint?
    PrefixMap(Map())
  }

  override def fromString(cs: CharSequence, format: String, base: Option[String]): Either[String,Rdf] = {
    Left("Cannot parse RDFFromWeb ")
  }

  override def serialize(format: String): Either[String,String] = {
    Left(s"Cannot serialize RDFFromWeb")
  }

  override def rdfTriples(): Set[RDFTriple] = {
    throw new Exception("Cannot obtain triples from RDFFromWeb ")
  }

  override def triplesWithSubject(node: RDFNode): Set[RDFTriple] = {
    if (node.isIRI) {
      val subj = node.toIRI
      val derefModel = ModelFactory.createDefaultModel
      RDFDataMgr.read(derefModel, subj.str)
      val model = QueryExecutionFactory.create(queryTriplesWithSubject(subj), derefModel).execConstruct()
      val triples = model2triples(model)
      log.debug("triples with subject " + subj + " =\n" + triples)
      triples
    } else
      throw new Exception("triplesWithSubject: node " + node + " must be a IRI")
  }

  override def triplesWithPredicate(p: IRI): Set[RDFTriple] = {
    val derefModel = ModelFactory.createDefaultModel
    RDFDataMgr.read(derefModel, p.str)
    val model = QueryExecutionFactory.create(queryTriplesWithPredicate(p), derefModel).execConstruct()
    model2triples(model)
  }

  override def triplesWithObject(node: RDFNode): Set[RDFTriple] = {
    if (node.isIRI) {
      val obj = node.toIRI
      val derefModel = ModelFactory.createDefaultModel
      RDFDataMgr.read(derefModel, obj.str)
      val model = QueryExecutionFactory.create(queryTriplesWithObject(obj), derefModel).execConstruct()
      model2triples(model)
    } else
      throw new Exception("triplesWithObject: node " + node + " must be a IRI")
  }

  override def triplesWithPredicateObject(p: IRI, node: RDFNode): Set[RDFTriple] = {
    if (node.isIRI) {
      val obj = node.toIRI
      val derefModel = ModelFactory.createDefaultModel
      RDFDataMgr.read(derefModel, obj.str)
      val model = QueryExecutionFactory.create(queryTriplesWithPredicateObject(p, obj), derefModel).execConstruct()
      model2triples(model)
    } else
      throw new Exception("triplesWithObject: node " + node + " must be a IRI")
  }

  override def getSHACLInstances(c: RDFNode): Seq[RDFNode] = {
    throw new Exception(s"Undefined getSHACLInstances at RDFFromWeb. Node $c")
  }

  override def hasSHACLClass(n: RDFNode, c: RDFNode): Boolean = {
    throw new Exception(s"Undefined hasSHACL at RDFFromWeb. Node: $n Class: $c")
  }

  override def nodesWithPath(p: SHACLPath): Set[(RDFNode, RDFNode)] = {
    throw new Exception(s"Undefined nodesWithPath at RDFFromWeb. Path: $p")
  }

  override def subjectsWithPath(p: SHACLPath, o: RDFNode): Set[RDFNode] = {
    throw new Exception(s"Undefined subjectsWithPath at RDFFromWeb. Path: $p")
  }

  override def objectsWithPath(subj: RDFNode, path: SHACLPath): Set[RDFNode] = {
    throw new Exception(s"Undefined objectsWithPath at RDFFromWeb. Path: $path")
  }

  override def checkDatatype(node: RDFNode, datatype: IRI): Either[String,Boolean] =
    JenaMapper.wellTypedDatatype(node, datatype)


  def model2triples(model: Model): Set[RDFTriple] = {
    model.listStatements().asScala.map(st => statement2triple(st)).toSet
  }

  def statement2triple(st: Statement): RDFTriple = {
    RDFTriple(
      jena2rdfnode(st.getSubject),
      property2iri(st.getPredicate),
      jena2rdfnode(st.getObject))
  }

  def property2iri(p: Property): IRI = {
    IRI(p.getURI)
  }

  def jena2rdfnode(r: JenaRDFNode): RDFNode = {
    if (r.isAnon) {
      BNode(r.asNode.getBlankNodeId.getLabelString)
    } else if (r.isURIResource) {
      IRI(r.asResource.getURI())
    } else if (r.isLiteral) {
      val lit = r.asLiteral
      if (lit.getDatatypeURI() == null) {
        StringLiteral(lit.getString())
      } else
        IRI(lit.getDatatypeURI()) match {
          case RDFNode.IntegerDatatypeIRI => IntegerLiteral(lit.getInt)
          case RDFNode.BooleanDatatypeIRI => BooleanLiteral(lit.getBoolean)
          case RDFNode.DoubleDatatypeIRI => DoubleLiteral(lit.getDouble())
          case RDFNode.LangStringDatatypeIRI => LangLiteral(lit.getLexicalForm, Lang(lit.getLanguage))
          case _ => DatatypeLiteral(lit.getLexicalForm, IRI(lit.getDatatypeURI))
        }
    } else
      throw new Exception("Unknown type of resource")
  }

  override def querySelect(queryStr: String): Either[String, List[Map[String,RDFNode]]] = Left(s"Unimplemented query on RDFFromWeb")
  override def queryAsJson(queryStr: String): Either[String, Json] = Left(s"Unimplemented query on RDFFromWeb")

  override def getNumberOfStatements(): Either[String,Int] = Left(s"Unimplemented number of statements of endpoint")

  override def isIsomorphicWith(other: RDFReader) = Left(s"Unimplemented isomorphic test in RDFFromWeb")


}