package info.kwarc.mmt.api.execution

import info.kwarc.mmt.api._
import objects._

class Stack {
   private var frames = List(Context.empty)
   
   def top = frames.head
   
   private def setTop(con: Context) {
     frames = con :: frames.tail
   }
   
   def newVariable(vd: VarDecl) {
      setTop(top ++ vd)
   }
   def assign(n: LocalName, value: Term) {
      val vd = top(n)
      val vdN = vd.copy(df = Some(value))
      val topNew = top.before(n) ::: vdN :: top.after(n)
   }
   def removeVariables(n: Int) {
      setTop(top.take(top.length - n))
   }
   
   def push {
     frames ::= Context.empty
   }
   def pop {
     if (frames.isEmpty)
       throw ImplementationError("empty stack")
     frames = frames.tail
   }
}