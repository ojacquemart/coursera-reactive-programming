/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection*/
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply
  
  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor with ActorLogging {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = {
    val props = BinaryTreeNode.props(0, initiallyRemoved = true)
    val actor = context.actorOf(props, "root-" + rootNum)
    rootNum += 1
    actor
  }

  var rootNum = 0
  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case Insert(requester, id, elem) => {
      root ! Insert(requester, id, elem)
    }
    case Contains(requester, id, elem) => {
      root ! Contains(requester, id, elem)
    }
    case Remove(requester, id, elem) => {
      root ! Remove(requester, id, elem)
    }
    case GC => {
      val newRoot = createRoot
      root ! CopyTo(newRoot)
      context.become(garbageCollecting(newRoot))
    }
  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case Insert(requester, id, elem) => {
      pendingQueue :+= Insert(requester, id, elem)
    }
    case Contains(requester, id, elem) => {
      pendingQueue :+= Contains(requester, id, elem)
    }
    case Remove(requester, id, elem) => {
      pendingQueue :+= Remove(requester, id, elem)
    }
    case CopyFinished => {
      root ! PoisonPill
      root = newRoot
      pendingQueue.foreach(newRoot ! _)
      pendingQueue = Queue.empty[Operation]
      context.become(normal)
    }
    case GC => ;         // ignore
  }
}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor with ActorLogging {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case Insert(requester, id, elem) => {
      propagateOperation(Insert(requester, id, elem), Inserter)
    }
    case Contains(requester, id, elem) => {
      propagateOperation(Contains(requester, id, elem), ContainsCheck)
    }
    case Remove(requester, id, elem) => {
      propagateOperation(Remove(requester, id, elem), Remover)
    }
    case CopyTo(treeNode) => {
      if (removed && subtrees.isEmpty) {
        sender ! CopyFinished
      } else {
        if (!removed) {
          treeNode ! Insert(self, 1, elem)
        }
        val children = subtrees.values.toSet
        children.foreach { _ ! CopyTo(treeNode) }
        context.become(copying(sender, children, insertConfirmed = removed))
      }
    }
  }

    // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(requester: ActorRef, expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case CopyFinished => {
      val newExpected = expected - sender
      if (newExpected.isEmpty && insertConfirmed) {
        requester ! CopyFinished
      } else {
        context.become(copying(requester, newExpected, insertConfirmed))
      }
    }
    case OperationFinished(id) => {
      if (expected.isEmpty) {
        requester ! CopyFinished
      } else {
        context.become(copying(requester, expected, insertConfirmed = true))
      }
    }
  }

  trait Actions {
    def equalsNode(requester: ActorRef, id: Int): Unit
    def newNode(position: Position, operation: Operation): Unit
  }

  object Inserter extends Actions {
    def equalsNode(requester: ActorRef, id: Int): Unit = {
      removed = false
      requester ! OperationFinished(id)
    }
    def newNode(position: Position, operation: Operation): Unit = {
      val props = BinaryTreeNode.props(operation.elem, initiallyRemoved = false)
      val newActor = context.actorOf(props, "node-" + operation.elem)
      subtrees += position -> newActor
      operation.requester ! OperationFinished(operation.id)
    }
  }

  object ContainsCheck extends Actions {
    def equalsNode(requester: ActorRef, id: Int): Unit = {
      requester ! ContainsResult(id, !removed)
    }
    def newNode(position: Position, operation: Operation): Unit = {
      operation.requester ! ContainsResult(operation.id, result = false)
    }
  }

  object Remover extends Actions {
    def equalsNode(requester: ActorRef, id: Int): Unit = {
      removed = true
      requester ! OperationFinished(id)
    }
    def newNode(position: Position, operation: Operation): Unit = {
      operation.requester ! OperationFinished(operation.id)
    }
  }

  def propagateOperation(operation: Operation, actions: Actions) {
    if (operation.elem == this.elem) {
      actions.equalsNode(operation.requester, operation.id)
    } else {
      val position = if (operation.elem < this.elem) Left else Right
      subtrees.get(position) match {
        case Some(actor) => actor ! operation
        case None => actions.newNode(position, operation)
      }
    }
  }
}
