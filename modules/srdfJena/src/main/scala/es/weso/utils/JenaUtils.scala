package es.weso.utils

import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.Resource
import org.apache.jena.sparql.syntax.ElementPathBlock
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.system.IRIResolver
import java.io.ByteArrayInputStream

import org.apache.jena.query.Query
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QueryFactory
import java.io.StringWriter

import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Property
import java.net.URI
import java.net.URL
import java.io.InputStream
import java.io.FileOutputStream

import org.apache.jena.atlas.AtlasException
import org.apache.jena.riot.RiotException
import org.apache.jena.query.ResultSet
import org.apache.jena.rdf.model.Literal

import scala.collection.JavaConverters._
import org.apache.jena.query.ParameterizedSparqlString
import org.apache.jena.reasoner.ReasonerRegistry
import org.apache.jena.sparql.core.{TriplePath, Var}
import org.apache.jena.sparql.path.Path
import org.apache.jena.util.{FileUtils => FileJenaUtils}

sealed abstract class ParserReport[+A, +B]

final case class Parsed[A](info: A)
  extends ParserReport[A, Nothing]

final case class NotParsed[B](error: B)
  extends ParserReport[Nothing, B]

object JenaUtils {

  lazy val RdfXML = FileJenaUtils.langXML
  lazy val RdfXMLAbbr = FileJenaUtils.langXMLAbbrev
  lazy val NTriple = FileJenaUtils.langNTriple
  lazy val Turtle = FileJenaUtils.langTurtle
  lazy val TTL = "TTL"
  lazy val N3 = "N3"

  // In Jena selectors, null represents any node
  lazy val any: RDFNode = null

  def extractModel(resource: Resource, model: Model): Model = {
    val nModel = ModelFactory.createDefaultModel()

    def inner(resource: Resource): Model = {
      val iterator2 = model.listStatements(resource, null, null)

      while (iterator2.hasNext()) {
        val stmt = iterator2.nextStatement();
        val subject = stmt.getSubject();
        val predicate = stmt.getPredicate();
        val objec = stmt.getObject();
        nModel.add(subject, predicate, objec)
        if (objec.isAnon) {
          inner(objec.asResource())
        }
      }
      nModel
    }
    inner(resource)
  }

  /*def statementAsString(statement: Statement, model: Model, preffix: Boolean): String = {
    val resource = try {
      val uri = statement.getResource.toString
      val preffixUri = statement.getResource.getNameSpace
      val preffixNS = model.getNsURIPrefix(statement.getResource.getNameSpace)
      val suffix = statement.getResource.getLocalName
      if (preffix && preffixUri != null)
        preffixNS + ":" + suffix
      else uri
    } catch {
      case _: ResourceRequiredException => null
    }
    if (resource == null) {
      try {
        if (preffix)
          statement.getLiteral().getValue().toString
        else statement.getLiteral().toString
      } catch {
        case _: LiteralRequiredException => null
      }
    } else resource
  } */

  def dereferenceURI(uri: String): InputStream = {
    val url = new URL(uri)
    val urlCon = url.openConnection()
    urlCon.setConnectTimeout(4000)
    urlCon.setReadTimeout(2000)
    urlCon.getInputStream()
  }

  def parseFromURI(
    uri: String,
    base: String = "",
    syntax: String = Turtle): Either[String,Model] = {
    uri2Model(uri, base, syntax) match {
      case Parsed(model) => Right(model)
      case NotParsed(err) => Left(err)
    }
  }

  def parseFromString(
    content: String,
    base: String = "",
    syntax: String = Turtle): Either[String,Model] = {
    str2Model(content, base, syntax) match {
      case Parsed(model) => Right(model)
      case NotParsed(err) =>Left("Cannot parse from string: " + content + "\nError: " + err + "\nSyntax: " + syntax)
    }
  }

  def uri2Model(
    uriName: String,
    base: String = "",
    syntax: String = Turtle): ParserReport[Model, String] = {
    try {
      val model = ModelFactory.createDefaultModel()
      Parsed(model.read(dereferenceURI(uriName), base, syntax))
    } catch {
      case e: AtlasException =>
        NotParsed("Error parsing URI " + uriName + " with syntax " + syntax + ".\n AtlasException: " + e.toString())
      case e: RiotException =>
        NotParsed("Exception parsing URI " + uriName + " with syntax " + syntax + ".\n RIOT Exception: " + e.toString())
      case e: Exception =>
        NotParsed("Exception parsing URI " + uriName + " with syntax " + syntax + ".\n Exception: " + e.toString())
    }
  }

  /**
   * Returns a RDF model after parsing a String
   */
  def str2Model(
    str: String,
    base: String = "",
    syntax: String = Turtle): ParserReport[Model, String] = {
    try {
      val model = ModelFactory.createDefaultModel()
      val stream = new ByteArrayInputStream(str.getBytes("UTF-8"))
      Parsed(model.read(stream, base, syntax))
    } catch {
      case e @ (_: AtlasException | _: RiotException) =>
        NotParsed("Bad formed with syntax " + syntax + ". " + e.getLocalizedMessage())
      case e: Exception =>
        NotParsed("Exception parsing from String " + str +
          " with syntax " + syntax + ". " + e.getLocalizedMessage())
    }
  }

  /**
   * Returns a RDF model after parsing an InputStream
   */
  def parseInputStream(
    stream: InputStream,
    base: String = "",
    syntax: String = Turtle): ParserReport[Model, String] = {
    try {
      val model = ModelFactory.createDefaultModel()
      Parsed(model.read(stream, base, syntax))
    } catch {
      case e @ (_: AtlasException | _: RiotException) =>
        NotParsed("Bad formed with syntax " + syntax + ". " + e.getLocalizedMessage())
      case e: Exception =>
        NotParsed("Exception parsing " +
          " with syntax " + syntax + ". " + e.getLocalizedMessage())
    }
  }

  def getLiteral(
    r: RDFNode,
    property: Property): String = {
    if (r.isResource()) {
      val res = r.asResource
      val stmt = res.getRequiredProperty(property)
      stmt match {
        case null =>
          throw new Exception("getName: " + res + " doesn't have value for property " + property + ".\n" +
            showResource(r.asResource))
        case _ =>
          if (stmt.getObject.isLiteral) stmt.getObject.asLiteral.getString
          else
            throw new Exception("getName: " + stmt.getObject + " is not a literal")
      }
    } else
      throw new Exception("getName: " + r + "is not a resource")
  }

  /*
   *
   */
  def getURI(r: RDFNode): URI = {
    if (r.isResource) {
      new URI(r.asResource.getURI)
    } else
      throw new Exception("getURI: Node " + r + " is not a resource")
  }

  /*
   * If there is a triple <r,p,u> and u is a URI, returns u
   * @param r RDFNode
   * @param property
   */
  def getObjectURI(
    r: RDFNode,
    p: Property): URI = {
    if (r.isResource()) {
      val resUri = r.asResource().getPropertyResourceValue(p)
      resUri match {
        case null =>
          throw new Exception("getURI: " + resUri + " doesn't have value for property " + p + ".\n" + showResource(r.asResource))
        case _ =>
          getURI(resUri)
      }
    } else
      throw new Exception("getURI: Node " + r + " is not a resource")
  }

  def getResource(node: RDFNode): Option[Resource] = {
    if (node.isResource()) Some(node.asResource())
    else None
  }

  /**
   * Given a class `cls`, obtains all nodes such as `node rdf:type/rdfs:subClassOf* cls`
   */
  def getSHACLInstances(cls: RDFNode, model: Model): Seq[RDFNode] = {
    val pss: ParameterizedSparqlString = new ParameterizedSparqlString()
    pss.setNsPrefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
    pss.setNsPrefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
    pss.setCommandText("SELECT ?n { ?n rdf:type/rdfs:subClassOf* ?c . }")
    pss.setParam("c", cls)
    val result = QueryExecutionFactory.create(pss.asQuery, model).execSelect
    result.asScala.toSeq.map(qs => qs.get("n"))
  }

  /**
   * Checks is a `node rdf:type/rdfs:subClassOf* cls`
   */
  def hasClass(n: RDFNode, c: RDFNode, model: Model): Boolean = {
    val pss: ParameterizedSparqlString = new ParameterizedSparqlString()
    pss.setNsPrefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
    pss.setNsPrefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
    pss.setCommandText("ASK { ?n rdf:type/rdfs:subClassOf* ?c . }")
    pss.setParam("n", n)
    pss.setParam("c", c)
    QueryExecutionFactory.create(pss.asQuery, model).execAsk
  }

  def getNodesFromPath(path: Path, model: Model): Seq[(RDFNode, RDFNode)] = {
    // Build the following query:
    // SELECT ?sub ?obj { ?sub ?path ?obj }
    val query: Query = QueryFactory.make()
    query.setQuerySelectType()
    val sub = Var.alloc("sub")
    val obj = Var.alloc("obj")
    val e = new ElementPathBlock()
    e.addTriple(new TriplePath(sub, path, obj))
    query.addResultVar(sub)
    query.addResultVar(obj)
    query.setQueryPattern(e)
    val result = QueryExecutionFactory.create(query, model).execSelect
    result.asScala.toSeq.map(qs => (qs.get("sub"), qs.get("obj")))
  }

  /**
    * Checks is a `node rdfs:subClassOf* cls`
    */
  def hasSubClass(subClass: RDFNode, superClass: RDFNode, model: Model): Boolean = {
    val pss : ParameterizedSparqlString = new ParameterizedSparqlString()
    pss.setNsPrefix("rdf","http://www.w3.org/1999/02/22-rdf-syntax-ns#")
    pss.setNsPrefix("rdfs","http://www.w3.org/2000/01/rdf-schema#")
    pss.setCommandText("ASK { ?sub rdfs:subClassOf* ?super . }")
    pss.setParam("sub",subClass)
    pss.setParam("super",superClass)
    QueryExecutionFactory.create(pss.asQuery, model).execAsk
  }

  def objectsFromPath(subj: RDFNode, path: Path, model: Model): Seq[RDFNode] = {
    // Build the following query:
    // SELECT ?obj { ?n ?path ?obj }
    val query: Query = QueryFactory.make()
    query.setQuerySelectType()
    val obj = Var.alloc("obj")
    val e = new ElementPathBlock()
    e.addTriple(new TriplePath(subj.asNode, path, obj))
    query.addResultVar(obj)
    query.setQueryPattern(e)
    val result = QueryExecutionFactory.create(query, model).execSelect
    result.asScala.toSeq.map(qs => qs.get("obj"))
  }

  def subjectsFromPath(obj: RDFNode, path: Path, model: Model): Seq[RDFNode] = {
    // Build the following query:
    // SELECT ?sub { ?sub ?path ?obj }
    val query: Query = QueryFactory.make()
    query.setQuerySelectType()
    val subj = Var.alloc("subj")
    val e = new ElementPathBlock()
    e.addTriple(new TriplePath(subj, path, obj.asNode))
    query.addResultVar(subj)
    query.setQueryPattern(e)
    val result = QueryExecutionFactory.create(query, model).execSelect
    result.asScala.toSeq.map(qs => qs.get("subj"))
  }

  /**
   * Shows infomation about a resource (list all the statements)
   */
  def showResource(resource: Resource): String = {
    val sb = new StringBuilder
    val iter = resource.listProperties()
    sb ++= ("Infor about: " + resource + "\n")
    while (iter.hasNext) {
      val st = iter.next
      sb ++= (st.toString + "\n")
    }
    sb.toString
  }

  /*
   * Parse a string to obtain a query
   */
  /*def parseQuery(
    str: String): Option[Query] = {
    try {
      val query = QueryFactory.create(str)
      Some(query)
    } catch {
      case _: Exception => None
    }
  } */

  def querySelectModel(query: Query, model: Model): ResultSet = {
    val qexec = QueryExecutionFactory.create(query, model)
    qexec.execSelect()
  }

  def querySelectModel(queryStr: String, model: Model): ResultSet = {
    val query = QueryFactory.create(queryStr)
    querySelectModel(query, model)
  }

  def queryConstructModel(queryStr: String, model: Model): Model = {
    val query = QueryFactory.create(queryStr)
    queryConstructModel(query, model)
  }

  def queryConstructModel(query: Query, model: Model): Model = {
    // val resultModel = ModelFactory.createDefaultModel
    val qexec = QueryExecutionFactory.create(query, model)
    qexec.execConstruct
  }

  /*
   * Convert a model to a String
   */
  def model2Str(
    model: Model,
    syntax: String = Turtle): String = {
    val strWriter = new StringWriter
    model.write(strWriter, syntax)
    strWriter.toString
  }

  /*
   * Write a model to a file
   */
  def model2File(
    model: Model,
    fileName: String,
    syntax: String = Turtle): Unit = {
    model.write(new FileOutputStream(fileName), syntax)
    ()
  }

  /* def getValuesOfType(r: Resource, m: Model) : Set[Resource] = {
  m.listResourcesWithProperty(rdf_type,r).toSet.asScala.toSet
 } */

  def findProperty(m: Model, r: Resource, p: Property): RDFNode = {
    val iter = m.listStatements(r, p, any)
    if (iter.hasNext) {
      val node = iter.next.getObject
      if (!iter.hasNext) node
      else throw
        new Exception("findProperty: Resource " + r + " has more than one value for property " + p)
    } else
      throw new Exception("findPropery: Resource " + r + " does not have value for property " + p)
  }

  def findProperty_asResource(m: Model, r: Resource, p: Property): Resource = {
    val v = findProperty(m, r, p)
    if (v.isResource) v.asResource
    else {
      throw new Exception("findProperty_asResource: Resource " + r + " has value " + v + " for property " + p + " which is not a resource")
    }
  }

  def findProperty_asLiteral(m: Model, r: Resource, p: Property): Literal = {
    val v = findProperty(m, r, p)
    if (v.isLiteral) v.asLiteral
    else {
      throw new Exception("findProperty_asLiteral: Resource " + r + " has value " + v + " for property " + p + " which is not a literal")
    }
  }

  def inference(rdf: Model, inference: String): Either[String, Model] = {
    inference match {
      case "RDFS" => {
        val inf = ModelFactory.createRDFSModel(rdf)
        Right(inf)
      }
      case "OWL" => {
        val reasoner = ReasonerRegistry.getOWLReasoner();
        val inf = ModelFactory.createInfModel(reasoner,rdf)
        Right(inf)
      }
      case _ => Left(s"Unsupported inference $inference")
    }
  }

  private val baseURI = IRIResolver.chooseBaseURI.toURI

  def relativizeStr(str: String): String = {
    val s = baseURI.relativize(new URI(str)).toString
    s
  }

  def relativizeNode(m: Model, n: RDFNode): RDFNode = {
    n match {
      case _ if n.isResource => relativizeResource(m, n.asResource())
      case _ => n
    }
  }

  def relativizeResource(m: Model, r: Resource): Resource = {
    r match {
      case _ if r.isURIResource => m.createResource(relativizeStr(r.getURI))
      case _ => r
    }
  }

  def relativizeProperty(m: Model, r: Property): Property = {
    m.createProperty(relativizeStr(r.getURI()))
  }

  def relativizeModel(m: Model):Model = {
    val r: Model = ModelFactory.createDefaultModel()
    r.setNsPrefixes(m.getNsPrefixMap)
    for (s <- m.listStatements().asScala) {
      val subj = relativizeResource(m, s.getSubject)
      val prop = relativizeProperty(r, s.getPredicate)
      val obj = relativizeNode(m, s.getObject)
      r.add(subj,prop,obj)
    }
    r
  }
}
