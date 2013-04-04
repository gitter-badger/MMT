package info.kwarc.mmt.api.parser
import info.kwarc.mmt.api._
import objects._

class MMTInterpolator(controller: frontend.Controller) {
   implicit def int2OM(i: Int) = OMI(i)
   implicit def floatt2OM(f: Double) = OMF(f)
   
   def parse(sc: StringContext, ts: List[Term], top: Option[TextNotation]) = {
         val strings = sc.parts.iterator
         val args = ts.iterator
         val buf = new StringBuffer(strings.next)
         var sub = Substitution()
         var i = 0
         while(strings.hasNext) {
            val name = LocalName("$_" + i.toString)
            sub = sub ++ Sub(name, args.next)
            buf.append(name)
            buf.append(strings.next)
            i += 1
        }
        val str = buf.toString
        val theory = controller.getBase match {
           case d: DPath => OMMOD(utils.mmt.mmtcd) 
           case p: MPath => OMMOD(p)
           case GlobalName(t,_) => t
           case CPath(par,_) => par match {
              case p: MPath => OMMOD(p)
              case GlobalName(t,_) => t
           }
        }
        val pu = ParsingUnit(SourceRef.anonymous(str), theory, sub.asContext, str, top) 
        val t = controller.termParser(pu)
        t ^ sub
   }
   
   implicit class MMTContext(sc: StringContext) {
      def mmt(ts: Term*): Term = parse(sc, ts.toList, None)
      def uom(ts: Term*): Term = {
         val t = mmt(ts : _*)
	      controller.uom.simplify(t)
      }
   }
   implicit class MMTContextForContexts(sc: StringContext) {
      def cont(ts: Term*) : Context = {
         val t = parse(sc, ts.toList, Some(TextNotation.contextNotation))
         t match {
            case OMBINDC(_,con, Nil) => con
            case _ => throw ParseError("not a context")
         }
      }
   }
}