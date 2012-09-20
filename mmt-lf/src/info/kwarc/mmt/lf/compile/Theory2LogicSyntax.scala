package info.kwarc.mmt.lf.compile

import info.kwarc.mmt.lf._
import info.kwarc.mmt.lf.compile._
import info.kwarc.mmt.api._
import modules._
import symbols._
import libraries._
import objects._
import utils._
import MyList._
import frontend._
import patterns._
import presentation._
import scala.sys.process._
import frontend.ToString

/*
 * translates an mmt theory to a logic syntax
 */
class Theory2LogicSyntax {
	case class TheoryLookupError(msg : String) extends java.lang.Throwable(msg) 
  
	/*
	 * translates a theory to a logic syntax LogicSyntax = (List[Category], CatRef, List[Declaration])
	 */
	def translateTheory(theo : Theory)  : LogicSyntax = {
	  val logsyn = theo match {
	    case theo : DeclaredTheory => {
	      val decList = theo.valueListNG
//	      val formCat = getForm(decList) match {
//	        case Some(x) => x
//	        case None => throw TheoryLookupError("could not determine formula category in: " + decList.toString)
//	      }
//	      println(formCat)
//	      println(getCats(decList))
//	      LogicSyntax(getCats(decList),formCat,null)
//	      val f = getForm(decList) match {
//	        case Some(x) => x
//	        case None => throw TheoryLookupError("no categories could be determined")
//	      }
	      
	      
	      val cats = getCatRefs(decList) map { x =>
	        getCat(decList,x)
	      } 
	      
//	      println( (decList mapPartial {x => x match { case x : Constant => Some(x); case _ => None }  }   )  map isFormCat )
	      
	      LogicSyntax(cats,CatRef("bool"),getDecls(decList))
	    }	      	   
	    case theo : DefinedTheory => throw TheoryLookupError("a DefinedTheory")
	    case _ =>  throw TheoryLookupError("unidentified theory") 
	  }
	 
	  
	 val c = new Compiler(logsyn)
	 
	 val l = c.get map { x => Haskell.decl(x) + "\n" }
	 
	 val ls : String = "\"" + l.toString() + "\""
    
	 println(ls)
//    "echo" + ls #> new java.io.File("/home/aivaras/Desktop/out.txt") !
	  
	  
	  logsyn
	}
	
	// helper function for term names
	// should only work when called on atomic types
	def head(t : Term) : CatRef = {	  
	  t match {
    	case ApplySpine(OMS(x),args) =>  CatRef(x.name.toPath)
	    case OMS(s) =>  CatRef(s.name.toPath)
	    case OMA(x,ar) => CatRef(x.toString())// ApplySpine does not work?
	    // in case of a function type, throw error
	    case _ => throw TheoryLookupError("this is a function type " + t.toString())
	  }
	}
	/*
	 * a very stupid Declaration parser
	 */
	def getDecls(sl : List[Symbol]) : List[Declaration] = {
	  sl.mapPartial{
	    case a : Pattern => {
	      val name = a.name.toString
	      val args = a.params.components.mapPartial{ v =>
	        v.tp
	      }
	      Some(Declaration(name,args.map{ x => head(x) }))
	    }
	    case _ => None  
	  }
	}
	
	// have so cat : Category, get connectives/functions of that Category
	def getCat(sl : List[Symbol], cat : CatRef) : Category = {
	  
	  // get connectives of category cat
	  val cons = sl mapPartial {
	    case a : Constant => a.tp match {
	      case Some(FunType(in, out)) => {
	        //check if 'out' is of the same category as 'cat'
	        if (head(out) == cat) {
	        
//	        	if (head(out) == cat.toString()) {
	        		if (in.isEmpty) None else {
	        			val catrefs : List[CatRef] = in.mapPartial {
	        				case (None, t) => t match {
	        				case Apply(OMS(x),args) =>  CatRef(x.name.toPath)
	        			  	case OMS(s) =>  CatRef(s.name.toPath)
	        				} 
	        			  	Some(head(t))
	        				case (Some(lname),t) => None//TODO unsupported
	        			}	
	        	
	        			Some(List(Connective(a.name.toString, catrefs)))
	        		}
//	        	} else None
	        } else None
	      }
	      case _ => None // not FunType
	    }
	    // produce ConstantSymbol
	    case a : Pattern => {
	      
	      
	      val cos = a.body.variables.toList.mapPartial{ v =>
	        	v.tp
	      }
	      
	      val args = a.params.variables.toList.mapPartial{ v =>
	    	  	v.tp
	      } map {x => head(x)}
	      
	      
	      val cs = cos mapPartial { x =>
	        if (head(x) == cat) 
	        	Some(ConstantSymbol(a.name.toString, head(x).toString,args))
	        else None
	      }
	      Some(cs)
	    } 
	    // not a Constant declaration or a Pattern, disregard
	    case _ => None 
	  }
	  // fill in a Category
	  Category(cat.toString,cons.flatten)
	}
	
	
	def getCatRefs(sl : List[Symbol]) : List[CatRef] = {
	  sl mapPartial {
	    case a : Constant =>  a.tp match {
	      	case Some(FunType(in, out)) => 
	      	  if (out == Univ(1) && in == List()) Some(CatRef(a.name.toString)) else None     
	      	case _ => None
	      }
	    
	    case _ => None
	  }
	  
	}
	
	def isFormCat(c : Constant) : Boolean = {
	  c.tp match {
	    case Some(FunType(in,out)) => in == List() && out == Univ(1)
	    case _ => false
	  }
	}
  
}
/*
 * test object
 */

object Test {
  case class TestError(msg : String) extends java.lang.Throwable(msg)  
  def main(args : Array[String]) = {
    val cont = new Controller()
    
    
    // add file to archive, go through structure!
    // read a source file
    val sourceFile1 = "/home/aivaras/TPTP/MMT/theories/source/plWPatterns.mmt"
//    val sourceFile1 = "/home/aivaras/TPTP/LogicAtlas/source/logics/propositional/syntax/syntax.elf"  
//    val sourceFile1 = "/home/aivaras/TPTP/MMT/theories/source/lf.mmt"  
//    val sourceFile2 = "/home/aivaras/TPTP/LogicAtlas/source/logics/propositional/syntax/syntax.elf" 
//    cont.handleLine("archive add /home/aivaras/TPTP/LogicAtlas")
//    cont.handleLine("archive latin source-structure")
//    cont.handleLine("achive latin compile")
       cont.handleLine("log console")
//        cont.handleLine("log+ parser")
    cont.handleLine("archive add /home/aivaras/TPTP/MMT/theories")  
    cont.handleLine("archive mmt source-structure")
    cont.handleLine("archive mmt compile")
    println("reading file " + sourceFile1)
    val file = scala.io.Source.fromFile(sourceFile1)
    val (doc, errl) = cont.textReader.readDocument(file,DPath(utils.URI("")))(cont.termParser.apply(_))
    println("errors: " + errl.toString)
    val path = DPath(utils.URI("http://cds.omdoc.org/foundational"))
//    println(cont.globalLookup.getAllPaths().toString)
    val theo =  cont.localLookup.getTheory(path ? "PLpatt") match {
      case d : DeclaredTheory => d
      case _ => throw TestError("attempted retrieving not a DeclaredTheory")
    }
//    println(theo.toString())
    val tls = new Theory2LogicSyntax()
//    println(theo.valueListNG foreach {a => a.toString})
    println(tls.translateTheory(theo))
        
    
//    cont.globalLookup.getTheory(path ? "PL")
    println("\n\nend")
    
    
    
  }
}
