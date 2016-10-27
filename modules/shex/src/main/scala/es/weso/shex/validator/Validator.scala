package es.weso.shex.validator
import cats._
import data._
import implicits._
import com.typesafe.scalalogging.LazyLogging
import es.weso.shex._
import es.weso.rdf._
import es.weso.rdf.nodes._
import es.weso.collection.Bag
import es.weso.rbe.interval.IntervalChecker
import es.weso.rbe.{BagChecker, Rbe}
import es.weso.utils.SeqUtils
import es.weso.shex.implicits.showShEx._
import ViolationError._
import es.weso.rdf.PREFIXES._
import es.weso.utils.SeqUtils._
import es.weso.shex.validator.table._
import ShExChecker._
import es.weso.shex.compact.CompactShow


/**
 * ShEx validator
 */
case class Validator(schema: Schema) extends LazyLogging {

  type ShapeChecker = ShapeExpr => CheckTyping
  type NodeShapeChecker = (RDFNode, Shape) => CheckTyping
  type CheckTyping = Check[ShapeTyping]
  type NodeChecker = Attempt => RDFNode => CheckTyping
  type Neighs = List[(Path,RDFNode)]
  type Arc = (Path,RDFNode)
  type Candidates = List[(Arc,Set[ConstraintRef])]
  type CandidateLine = List[(Arc,ConstraintRef)]
  type NoCandidates = List[Arc]
  type Bag_ = Bag[ConstraintRef]
  type Rbe_ = Rbe[ConstraintRef]
  type BagChecker_ = BagChecker[ConstraintRef]

  lazy val sh_targetNode = sh + "targetNode"

  lazy val ignoredPathsClosed : List[Path] =
    List(Inverse(sh_targetNode))

  def checkTargetNodeDeclarations: CheckTyping = for {
    rdf <- getRDF
    nodeLabels <- getTargetNodeDeclarations(rdf)
    ts <- checkAll(
      nodeLabels.map{
       case (node,label) => checkNodeLabel(node,label)
      })
    t <- combineTypings(ts)
  } yield t

  // TODO
  def getTargetNodeDeclarations(
    rdf: RDFReader
  ): Check[List[(RDFNode,ShapeLabel)]] = {
    checkAll(rdf.triplesWithPredicate(sh_targetNode).
      map(t => (t.obj,mkShapeLabel(t.subj))).
      toList.map(checkPair2nd)
    )
  }

  def mkShapeLabel(n: RDFNode): Check[ShapeLabel] = {
    n match {
      case i:IRI => ok(IRILabel(i))
      case b:BNodeId => ok(BNodeLabel(b))
      case _ => {
        errStr(s"mkShapeLabel: Node ${n.show} can't be a shape")
      }
    }
  }

  def getShape(label: ShapeLabel): Check[ShapeExpr] =
    schema.getShape(label) match {
      case None => errStr[ShapeExpr](s"Can't find shape ${label.show} is Schema:\n${schema.show}")
      case Some(shape) => ok(shape)
    }

  def checkNodeLabel(node: RDFNode, label: ShapeLabel): CheckTyping = {
    logger.info(s"nodeLabel. Node: ${node.show} Label: ${label.show}")
    for {
      typing <- getTyping
      newTyping <- if (typing.hasType(node, label))
        ok(typing)
      else if (typing.hasNoType(node, label)) {
        errStr[ShapeTyping](s"Failed because ${node.show} doesn't match shape ${label.show}")
      } else for {
        shapeExpr <- getShape(label)
        shapeType = ShapeType(shapeExpr, Some(label), schema)
        attempt = Attempt(NodeShape(node, shapeType), None)
        t <- runLocal(checkNodeShapeExpr(attempt, node, shapeExpr), _.addType(node, shapeType))
      } yield t
    } yield newTyping
  }

  def errStr[A](msg: String): Check[A] =
    err[A](ViolationError.msgErr(msg))

  def checkNodeShapeExpr(attempt: Attempt, node: RDFNode, s: ShapeExpr): CheckTyping = {
    logger.info(s"${attempt.show}, node: ${node.show}, shapeExpr: ${CompactShow.showShapeExpr(s, schema.prefixMap)}")
    s match{
      case ShapeOr(ses) => checkOr(attempt,node,ses)
      case ShapeAnd(ses) => checkAnd(attempt,node,ses)
      case ShapeNot(s) => checkNot(attempt,node,s)
      case nc: NodeConstraint => checkNodeConstraint(attempt,node,nc)
      case s: Shape => checkShape(attempt,node,s)
      case ShapeRef(ref) => checkRef(attempt,node,ref)
      case s: ShapeExternal => errStr(s"Not implemented ShapeExternal ${attempt.show}")
    }
  }

  def checkAnd(attempt: Attempt,
               node: RDFNode,
               ses:List[ShapeExpr]): CheckTyping = for {
    ts <- checkAll(ses.map(se => checkNodeShapeExpr(attempt,node,se)))
    t <- combineTypings(ts)
  } yield t

  def checkOr(attempt: Attempt,
               node: RDFNode,
               ses:List[ShapeExpr]): CheckTyping = {
    val vs = ses.map(se => checkNodeShapeExpr(attempt,node,se))
    for {
      t1 <- checkSome(vs,ViolationError.msgErr(s"None of the alternatives of OR($ses) is valid for node $node"))
      t2 <- addEvidence(attempt.nodeShape, s"$node passes OR")
      t3 <- combineTypings(Seq(t1, t2))
    } yield t3
  }

  def checkNot(attempt: Attempt,
               node: RDFNode,
               s: ShapeExpr): CheckTyping = {
    val parentShape = attempt.nodeShape.shape
    val check: CheckTyping = checkNodeShapeExpr(attempt, node, s)
    val handleError: ViolationError => Check[ShapeTyping] = e => for {
      t1 <- addNotEvidence(NodeShape(node, ShapeType(s, None, schema)), e,
        s"$node doesn't satisfy ${s}. Negation declared in ${parentShape}. Error: $e")
      t2 <- addEvidence(attempt.nodeShape, s"$node satisfies not(${s})")
      t <- combineTypings(List(t1, t2))
    } yield t
    val handleNotError: ShapeTyping => Check[ShapeTyping] = t =>
      errStr(s"Failed NOT($s) because node $node satisfies it")
    cond(check, handleNotError, handleError)
  }

  def checkRef(attempt: Attempt,
               node: RDFNode,
               ref:ShapeLabel): CheckTyping =
    checkNodeLabel(node,ref)

  def checkNodeConstraint(attempt: Attempt,
                          node: RDFNode,
                          s: NodeConstraint): CheckTyping =
  for {
    _ <- getTyping
    t1 <- optCheck(s.nodeKind, checkNodeKind(attempt,node), getTyping)
  } yield t1


  def checkNodeKind(attempt: Attempt, node: RDFNode)(nk: NodeKind): CheckTyping = {
    logger.info(s"NodeKind ${node.show} ${nk.show}")
    nk match {
      case IRIKind => {
        logger.info(s"checking if ${node.show} is an IRI")
        checkCond(node.isIRI, attempt,
          msgErr(s"${node.show} is not an IRI"), s"${node.show} is an IRI")
      }
      case BNodeKind =>
        checkCond(node.isBNode, attempt,
          msgErr(s"${node.show} is not a BlankNode"), s"${node.show} is a BlankNode")
      case NonLiteralKind =>
        checkCond(! node.isLiteral, attempt,
          msgErr(s"${node.show} is a literal but should be a NonLiteral"),
          s"${node.show} is NonLiteral")
      case LiteralKind =>
        checkCond(node.isLiteral, attempt,
          msgErr(s"${node.show} is not an Literal"),
          s"${node.show} is a Literal")
    }
  }

  def checkShape(attempt: Attempt, node: RDFNode, s: Shape): CheckTyping = {
    val tripleExpr = s.tripleExpr
    for {
      neighs <- getNeighs(node)
      tableRbe <- mkTable(tripleExpr)
      (cTable,rbe) = tableRbe
      bagChecker = IntervalChecker(rbe)
      csRest <- calculateCandidates(neighs,cTable)
      (candidates,rest) = csRest
      _ <- checkRests(rest,s.extraPaths,s.isClosed,ignoredPathsClosed)
      typing <- checkCandidates(attempt,bagChecker,cTable)(candidates)
//      t <- optCheck(s.expression, checkTripleExpr(attempt, node), getTyping)
    } yield typing
  }

  def checkRests(rests: List[(Path,RDFNode)],
                extras: List[Path],
                isClosed: Boolean,
                ignoredPathsClosed: List[Path]
               ): Check[Unit] = for {
    _ <- checkAll(rests.map(checkRest(_, extras, isClosed, ignoredPathsClosed)))
  } yield ()

  def checkRest(rest: (Path,RDFNode),
                extras: List[Path],
                isClosed: Boolean,
                ignoredPathsClosed: List[Path]
                ): Check[Unit] = {
    val restPath = rest._1
    if (isClosed) {
      if (ignoredPathsClosed.contains(restPath) || extras.contains(restPath)) {
        ok(())
      } else {
        errStr(s"Closed shape. But rest ${restPath.show} is not in $ignoredPathsClosed or $extras")
      }
    } else ok(())
  }


  def mkTable(t: TripleExpr): Check[(CTable,Rbe_)] =
    ok(CTable.mkTable(t))

  /**
    * Calculates the sequence of candidates
    * Example: Neighs (p,x1),(p,x2),(q,x2),(r,x3)
    *   Table: { constraints: C1 -> IRI, C2 -> ., paths: p -> List(C1,C2), q -> C1 }
    *   Result: x1
    * @param neighs
    * @param table
    * @return a tuple (cs,rs) where cs is the list of candidates and rs is the nodes that didn't match any
    */
  def calculateCandidates(neighs: Neighs, table: CTable): Check[(Candidates, NoCandidates)] = {
   val ls = neighs.map {
     case arc@(path, node) => (arc, table.paths.get(path).getOrElse(Set()))
   }
   if (ls.isEmpty) {
     errStr(s"No candidates match. Neighs: ${neighs.show}, Table: ${table.show}")
   } else {
     val (cs,rs) = ls.partition{case (_,s) => !s.isEmpty}
     ok((cs, rs.map { case (arc, _) => arc }))
   }
  }

  def checkCandidates(attempt: Attempt, bagChecker:BagChecker_, table: CTable)(cs: Candidates): CheckTyping = {
    val as: List[CandidateLine] = SeqUtils.transpose(cs)
    val checks: List[CheckTyping] = as.map(checkCandidateLine(attempt, bagChecker, table)(_))
    checkSome(checks,ViolationError.msgErr(s"No candidate matched from $cs"))
  }

  def checkCandidateLine(attempt: Attempt, bagChecker: BagChecker_, table: CTable)(cl: CandidateLine): CheckTyping = {
    val bag = Bag.toBag(cl.map(_._2))
    // parameter open=false because open has been checked before
    bagChecker.check(bag,false) match {
      case Right(_) => {
        val nodeShapes: List[(RDFNode,ShapeExpr)] =
          filterOptions(cl.map{ case (arc,cref) => {
            val (path,node) = arc
            (node,table.getShapeExpr(cref))
          }})
        val checkNodeShapes: List[CheckTyping] =
          nodeShapes.map{ case (node,shapeExpr) =>
            checkNodeShapeExpr(attempt,node,shapeExpr)
          }
        for {
          ts <- checkAll(checkNodeShapes)
          t <- combineTypings(ts)
        } yield t
      }
      case Left(err) => errStr(s"Doesn't match Rbe ${bagChecker.rbe} with bag $bag. Err: $err")
    }
  }

  def getNeighs(node: RDFNode): Check[Neighs] = for {
    rdf <- getRDF
  } yield {
    val outgoing: List[(Path,RDFNode)] = rdf.triplesWithSubject(node).
      map(t => (Direct(t.pred),t.obj)).toList
    val incoming: List[(Path,RDFNode)] = rdf.triplesWithObject(node).
      map(t => (Inverse(t.pred),t.obj)).toList
    outgoing ++ incoming
  }

def checkTripleConstraint(
    attempt: Attempt,
    node: RDFNode)
    (t: TripleConstraint): CheckTyping = for {
  rdf <- getRDF
  triples =
    if (t.direct) rdf.triplesWithSubjectPredicate(node,t.predicate)
    else rdf.triplesWithPredicateObject(t.predicate,node)
  noTriples = triples.size
  t <- checkCond(noTriples >= t.min && t.max.biggerThanOrEqual(noTriples),
           attempt,
           msgErr(s"Number of triples with predicate ${t.predicate.show}=$noTriples not in (${t.min},${t.max})"),
           s"No. of triples with predicate ${t.predicate.show}= $noTriples between (${t.min},${t.max})")
 } yield t

 def checkCond(
    condition: Boolean,
    attempt: Attempt,
    error: ViolationError,
    evidence: String): CheckTyping = for {
    _ <- validateCheck(condition, error)
    newTyping <- addEvidence(attempt.nodeShape, evidence)
  } yield newTyping

  def addEvidence(nodeShape: NodeShape, msg: String): Check[ShapeTyping] = {
    for {
      t <- getTyping
      _ <- addLog(List((nodeShape, msg)))
    } yield t.addEvidence(nodeShape.node, nodeShape.shape, msg)
  }

  def addNotEvidence(nodeShape: NodeShape, e: ViolationError, msg: String): Check[ShapeTyping] = {
    val node = nodeShape.node
    val shape = nodeShape.shape
    for {
      t <- getTyping
      _ <- addLog(List((nodeShape, msg)))
    } yield t.addNotEvidence(node, shape, e)
  }


  def runLocal[A](c: Check[A], f: ShapeTyping => ShapeTyping): Check[A] =
    local(f)(c)

  def getRDF: Check[RDFReader] = getConfig // ask[Comput,RDFReader]

  def getTyping: Check[ShapeTyping] = getEnv // ask[Comput,ShapeTyping]

  def combineTypings(ts: Seq[ShapeTyping]): Check[ShapeTyping] = {
    ok(ShapeTyping.combineTypings(ts))
  }

  def runCheck[A: Show](
    c: Check[A],
    rdf: RDFReader
  ): CheckResult[ViolationError, A, Log] = {
    val initial: ShapeTyping = Monoid[ShapeTyping].empty
    val r = run(c)(rdf)(initial)
    CheckResult(r)
  }

  implicit lazy val showRDFNode = new Show[RDFNode] {
    override def show(n: RDFNode): String = {
      n match {
        case i: IRI => schema.qualify(i)
        case l: Literal => l.getLexicalForm
        case b: BNodeId => "_:" + b.getLexicalForm
      }
    }
  }

  implicit lazy val showShapeLabel = new Show[ShapeLabel] {
    override def show(lbl: ShapeLabel): String = {
      lbl match {
        case IRILabel(iri) => Show[RDFNode].show(iri)
        case BNodeLabel(bnode) => Show[RDFNode].show(bnode)
      }
    }
  }

  implicit lazy val showPath = new Show[Path] {
    override def show(p: Path): String = {
      p match {
        case Direct(iri) => schema.qualify(iri)
        case Inverse(iri) => "^" + schema.qualify(iri)
      }
    }
  }

  implicit lazy val showAttempt = new Show[Attempt] {
    override def show(a: Attempt): String = {
      val showPath: String = a.path match {
        case None => ""
        case Some(p) => ", path: " + p.show
      }
      s"Attempt: node: ${a.node.show}, shape: ${a.shape.show}${showPath}"
    }
  }

  lazy val emptyTyping: ShapeTyping =
    Monoid[ShapeTyping].empty

  // Fails if there is any error
  def validateAll(rdf: RDFReader): CheckResult[ViolationError, ShapeTyping, Log] = {
    implicit def showPair = new Show[(ShapeTyping, Evidences)] {
      def show(e: (ShapeTyping, Evidences)): String = {
        s"Typing: ${e._1}\n Evidences:\n${e._2}"
      }
    }
    runCheck(checkTargetNodeDeclarations, rdf)
  }

}

object Validator {

  def empty = Validator(schema = Schema.empty)

  type Result[A] = Xor[NonEmptyList[ViolationError], List[(A, Evidences)]]

  def isOK[A](r: Result[A]): Boolean =
    r.isRight && r.toList.isEmpty == false

}
