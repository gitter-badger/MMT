package info.kwarc.mmt.api.archives

import info.kwarc.mmt.api._
import Level.Level
import frontend._
import utils._

sealed abstract class BuildTargetModifier {
  def toString(dim: String): String
}

case object Clean extends BuildTargetModifier {
  def toString(dim: String) = "-" + dim
}

case class Update(errorLevel: Level, dryRun: Boolean = false) extends BuildTargetModifier {
  def key: String =
    if (errorLevel <= Level.Force) ""
    else if (errorLevel < Level.Ignore) "!" else "*"

  def toString(dim: String) = dim + key
}

case object Build extends BuildTargetModifier {
  def toString(dim: String) = dim
}

case class BuildDepsFirst(up: Update) extends BuildTargetModifier {
  def toString(dim: String) = dim + "&"
}

/** A BuildTarget provides build/update/clean methods that generate one or more dimensions in an [[Archive]]
  * from an input dimension.
  */
abstract class BuildTarget extends FormatBasedExtension {
  /** a string identifying this build target, used for parsing commands, logging, error messages */
  def key: String

  /** default file extension to determine the input of subsequent targets within meta targets
    *
    * needs to be overwritten by build targets for sources
    * i.e. for "rbuild latexml_stex-omdoc" latexml produces latexml/ex.omdoc from source/ex.tex
    * therefore the inPath for stex-omdoc must be changed from ex.tex (for latexml)
    * to ex.omdoc (for stex-omdoc) in the pipeline described by the meta target
    * */
  def defaultFileExtension: String = "omdoc"

  def isApplicable(format: String): Boolean = format == key

  /** defaults to the key */
  override def logPrefix: String = key

  /** build this target in a given archive */
  def build(a: Archive, in: FilePath)

  /** update this target in a given archive */
  def update(a: Archive, up: Update, in: FilePath)

  /** clean this target in a given archive */
  def clean(a: Archive, in: FilePath)

  /** build this target in a given archive but build dependencies first */
  def buildDepsFirst(arch: Archive, up: Update, in: FilePath) {}

  /** the main function to run the build target
    *
    * @param modifier chooses build, clean, or update
    * @param arch the archive to build on
    * @param in the folder inside the archive's inDim folder to which building in restricted (i.e., Nil for whole archive)
    */
  def apply(modifier: BuildTargetModifier, arch: Archive, in: FilePath) {
    modifier match {
      case up: Update => update(arch, up, in)
      case Clean => clean(arch, in)
      case Build => build(arch, in)
      case BuildDepsFirst(lvl) => buildDepsFirst(arch, lvl, in)
    }
  }

  /** auxiliary method for deleting a file */
  protected def delete(f: File) {
    if (f.exists) {
      log("deleting " + f)
      f.delete
    }
  }
}

/** auxiliary type to represent the parameters and result of building a file/directory
  *
  * @param inFile the input file
  * @param inPath the path of the input file inside the archive, relative to the input dimension
  * @param children the build tasks of the children if this task refers to a directory
  * @param outFile the intended output file
  * @param outPath the output file inside the archive, relative to the archive root.
  * @param errorCont BuildTargets should report errors here
  */
class BuildTask(val archive: Archive, val inFile: File, val children: Option[List[BuildTask]], val inPath: FilePath,
                val outFile: File, val outPath: FilePath, val errorCont: OpenCloseHandler) {
  /** build targets should set this to true if they skipped the file so that it is not passed on to the parent directory */
  var skipped = false
  /** the narration-base of the containing archive */
  val base = archive.narrationBase

  /** the MPath corresponding to the inFile if inFile is a file in a content-structured dimension */
  def contentMPath: MPath = Archive.ContentPathToMMTPath(inPath)

  /** the DPath corresponding to the inFile if inFile is a folder in a content-structured dimension */
  def contentDPath: DPath = Archive.ContentPathToDPath(inPath)

  /** the DPath corresponding to the inFile if inFile is in a narration-structured dimension */
  def narrationDPath: DPath = DPath(base / inPath.segments)

  def isDir = children.isDefined

  /** the name of the folder if inFile is a folder */
  def dirName: String = outFile.filepath.dirPath.baseName
}

/** This abstract class provides common functionality for [[BuildTarget]]s that traverse all files in the input dimension.
  *
  * It implements BuildTarget in terms of the abstract method buildFile called to build a file in the archive.
  * It is also possible to override the method buildDir to post process directory content.
  */
abstract class TraversingBuildTarget extends BuildTarget {
  /** the input dimension/archive folder */
  def inDim: ArchiveDimension

  /** the output archive folder */
  def outDim: ArchiveDimension

  /** if true, multiple files/folders are built in parallel */
  def parallel: Boolean = false

  /** the file extension used for generated files, defaults to outDim, override as needed */
  def outExt: String = outDim match {
    case Dim(path@_*) => path.last
    case d => d.toString
  }

  /** the name that is used for the special file representing the containing folder, empty by default */
  protected val folderName = ""

  protected def getOutFile(a: Archive, inPath: FilePath) = (a / outDim / inPath).setExtension(outExt)

  protected def getFolderOutFile(a: Archive, inPath: FilePath) = a / outDim / inPath / (folderName + "." + outExt)

  protected def getOutPath(a: Archive, outFile: File) = outFile.filepath

  protected def getErrorFile(a: Archive, inPath: FilePath): File = (a / errors / key / inPath).addExtension("err")

  protected def getErrorFile(d: BuildDependency): File = (d.archive / errors / d.key / d.filePath).addExtension("err")

  protected def getFolderErrorFile(a: Archive, inPath: FilePath) = a / errors / key / inPath / (folderName + ".err")

  /** some logging for lmh */
  protected def logResult(s: String) {
    log(s, Some("result"))
  }

  /** there is no inExt, instead we test to check which files should be used;
    * this is often a test for the file extension
    *
    * This must be such that all auxiliary files are skipped.
    * see defaultFileExtension if you need an inExt (for meta targets)
    */
  def includeFile(name: String): Boolean

  /** true by default; override to skip auxiliary directories */
  def includeDir(name: String): Boolean = true

  /** the main abstract method that implementations must provide: builds one file
    *
    * @param bf information about input/output file etc
    * @return a build result TODO
    */
  def buildFile(bf: BuildTask): BuildResult

  /** similar to buildFile but called on every directory (after all its children have been processed)
    *
    * This does nothing by default and can be overridden if needed.

    * @param bd information about input/output file etc
    * @param builtChildren results from building the children
    */
  def buildDir(bd: BuildTask, builtChildren: List[BuildTask]): BuildResult = BuildSuccess(Nil)

  /** abstract method to compute the estimated direct dependencies */
  def getDeps(bf: BuildTask): Set[Dependency] =
    Set.empty

  /** entry point for recursive building */
  def build(a: Archive, in: FilePath = EmptyPath) {
    build(a, in, None)
  }

  def build(a: Archive, in: FilePath, errorCont: Option[ErrorHandler]) {
    buildAux(in, a, errorCont)
  }

  /** recursive building */
  private def buildAux(in: FilePath, a: Archive, eCOpt: Option[ErrorHandler]) {
    //build every file
    a.traverse[BuildTask](inDim, in, TraverseMode(includeFile, includeDir, parallel))({
      case Current(inFile, inPath) =>
        val bf = makeBuildTask(a, inPath, inFile, None, eCOpt)
        val deps = getDeps(bf)
        val qt = new QueuedTask(this, bf, deps)
        controller.buildManager.addTask(qt)
        bf
    }, {
      case (Current(inDir, inPath), builtChildren) =>
        val bd = makeBuildTask(a, inPath, inDir, Some(builtChildren), None)
        val qt = new QueuedTask(this, bd, Nil)
        controller.buildManager.addTask(qt)
        bd
    })
  }

  private def makeHandler(a: Archive, inPath: FilePath, isDir: Boolean = false) = {
    val errFileName = if (isDir) getFolderErrorFile(a, inPath)
    else getErrorFile(a, inPath)
    new ErrorWriter(errFileName, Some(report))
  }

  /** create a [[BuildTask]] form some if its components
    *
    * @param eCOpt optional additional [[ErrorHandler]], errors are always written to errors dimension
    */
  protected def makeBuildTask(a: Archive, inPath: FilePath, inFile: File,
                              children: Option[List[BuildTask]], eCOpt: Option[ErrorHandler]): BuildTask = {
    val errorWriter = makeHandler(a, inPath, children.isDefined)
    val errorCont = eCOpt match {
      case None => errorWriter
      case Some(eC) => new MultipleErrorHandler(List(eC, errorWriter))
    }
    val outFile = if (children.isDefined) getFolderOutFile(a, inPath) else getOutFile(a, inPath)
    val outPath = getOutPath(a, outFile)
    new BuildTask(a, inFile, children, inPath, outFile, outPath, errorCont)
  }

  /** like buildFile but with error handling, logging, etc.  */
  def runBuildTask(bt: BuildTask): BuildResult = {
    if (!bt.isDir) {
      val prefix = "[" + inDim + " -> " + outDim + "] "
      report("archive", prefix + bt.inFile + " -> " + bt.outFile)
      bt.outFile.up.mkdirs
    }
    var res: BuildResult = BuildResult.empty
    bt.errorCont.open
    try {
      res = bt.children match {
        case None => buildFile(bt)
        case Some(children) =>
          buildDir(bt, children)
      }
    } catch {
      case e: Error => bt.errorCont(e)
      case e: Exception =>
        val le = LocalError("unknown build error: " + e.getMessage).setCausedBy(e)
        bt.errorCont(le)
        res = BuildFailure(Nil, Nil)
    } finally {
      bt.errorCont.close
    }
    if (!bt.isDir) controller.notifyListeners.onFileBuilt(bt.archive, this, bt.inPath)
    res
  }

  /** additional method that implementations may provide: cleans one file
    *
    * deletes the output and error file by default, may be overridden to, e.g., delete auxiliary files
    *
    * @param a the containing archive
    * @param curr the inDim whose output is to be deleted
    */
  def cleanFile(a: Archive, curr: Current) {
    val inPath = curr.path
    val outFile = getOutFile(a, inPath)
    delete(outFile)
    delete(getErrorFile(a, inPath))
    controller.notifyListeners.onFileBuilt(a, this, inPath)
  }

  /** additional method that implementations may provide: cleans one directory
    *
    * does nothing by default
    *
    * @param a the containing archive
    * @param curr the outDim directory to be deleted
    */
  def cleanDir(a: Archive, curr: Current) {
    delete(getFolderErrorFile(a, curr.path))
  }

  /** recursively delete output files in parallel (!) */
  def clean(a: Archive, in: FilePath = EmptyPath) {
    a.traverse[Unit](inDim, in, TraverseMode(includeFile, includeDir, parallel = true))(
      { c => cleanFile(a, c) }, { case (c, _) => cleanDir(a, c) })
  }

  private def modified(inFile: File, errorFile: File): Boolean = {
    val mod = Modification(inFile, errorFile)
    mod == Modified || mod == Added
  }

  /** @return status of input file, obtained by comparing to error file */
  private def hadErrors(errorFile: File, errorLevel: Level): Boolean =
    if (errorLevel > Level.Fatal) false // nothing is more severe than a fatal error
    else
      errorFile.exists && ErrorReader.getBuildErrors(errorFile, errorLevel, None).nonEmpty

  /** recursively reruns build if the input file has changed
    *
    * the decision is made based on the time stamps and the system's last-modified date
    */
  def update(a: Archive, up: Update, in: FilePath = EmptyPath) {
    a.traverse[(Boolean, BuildTask)](inDim, in, TraverseMode(includeFile, includeDir, parallel))({
      case c@Current(inFile, inPath) =>
        val errorFile = getErrorFile(a, inPath)
        val errs = hadErrors(errorFile, up.errorLevel)
        val bf = makeBuildTask(a, inPath, inFile, None, None)
        val res = up.errorLevel <= Level.Force || modified(inFile, errorFile) || errs ||
          getDeps(bf).exists {
            case bd: BuildDependency =>
              val errFile = getErrorFile(bd)
              modified(errFile, errorFile)
            case ForeignDependency(fFile) => modified(fFile, errorFile)
            case _ => false
          }
        val outPath = getOutPath(a, getOutFile(a, inPath))
        if (res) if (up.dryRun) logResult("out-dated " + outPath) else runBuildTask(bf)
        else logResult("up-to-date " + outPath)
        (res, bf)
    }, { case (c@Current(inDir, inPath), childChanged) =>
      val changes = childChanged.exists(_._1)
      val children = childChanged.map(_._2)
      val bd = makeBuildTask(a, inPath, inDir, Some(children), None)
      if (changes) {
        runBuildTask(bd)
      }
      (changes, bd)
    })
  }

  protected def getFilesRec(a: Archive, in: FilePath): Set[Dependency] = {
    val inFile = a / inDim / in
    if (inFile.isDirectory)
      inFile.list.flatMap(n => getFilesRec(a, FilePath(in.segments ::: List(n)))).toSet
    else if (inFile.isFile && includeFile(inFile.getName) && includeDir(inFile.up.getName))
      Set(BuildDependency(key, a, in))
    else Set.empty
  }

  def makeBuildTask(a: Archive, inPath: FilePath): BuildTask = {
    makeBuildTask(a, inPath, a / inDim / inPath, None, None)
  }

  def getAnyDeps(key: String, dep: BuildDependency): Set[Dependency] = {
    if (dep.key == key)
      getDeps(makeBuildTask(dep.archive, dep.filePath))
    else controller.extman.getOrAddExtension(classOf[BuildTarget], key, Nil) match {
      case bt: TraversingBuildTarget => bt.getDeps(bt.makeBuildTask(dep.archive, dep.filePath))
      //TODO resolve non-simple dependencies
      case _ => Set.empty
    }
  }

  def getTopsortedDeps(key: String, args: Set[Dependency]): List[Dependency] = {
    var visited: Set[Dependency] = Set.empty
    var unknown = args
    var deps: Map[Dependency, Set[Dependency]] = Map.empty
    while (unknown.nonEmpty) {
      val p = unknown.head
      val ds: Set[Dependency] = p match {
        case bd: BuildDependency => getAnyDeps(key, bd)
        case _ => Set.empty
      }
      deps += ((p, ds))
      visited += p
      unknown -= p
      unknown ++= ds.diff(visited)
    }
    Relational.flatTopsort(controller, deps)
  }

  override def buildDepsFirst(a: Archive, up: Update, in: FilePath = EmptyPath) {
    val ts = getTopsortedDeps(key, getFilesRec(a, in))
    ts.foreach {
      case bd: BuildDependency => if (bd.key == key) update(bd.archive, up, bd.filePath)
      else controller.extman.getOrAddExtension(classOf[BuildTarget], key, Nil) match {
        case bt: TraversingBuildTarget => bt.update(bd.archive, up, bd.filePath)
        case _ => log("build target not found: " + bd.key)
      }
      case _ =>
    }
  }
}

/** a build target that chains multiple other targets */
class MetaBuildTarget extends BuildTarget {
  private var _key = ""
  private var targets: List[BuildTarget] = Nil
  var startArgs: List[String] = Nil

  def key: String = _key

  /** first argument: the key of this build target
    * remaining arguments: the build targets to chain
    */
  override def start(args: List[String]) {
    startArgs = args
    _key = args.headOption.getOrElse(
      throw LocalError("at least one argument required")
    )
    targets = args.tail.map(k =>
      controller.extman.get(classOf[BuildTarget]).find(_.getClass.getName == k).getOrElse {
        throw LocalError("unknown target: " + k)
      })
  }

  /** @return the path to pass to the target t, override as needed */
  def path(a: Archive, t: BuildTarget, inPath: FilePath): FilePath = {
    t match {
      case t: TraversingBuildTarget if t.inDim != content =>
        val file = a / t.inDim / inPath
        val in = if (file.isDirectory || t.includeFile(inPath.baseName)) inPath
        else
          inPath.toFile.setExtension(t.defaultFileExtension).filepath
        log("trying " + t.key + " in " + a.id + " with " + (t.inDim :: in.segments).mkString("/"))
        in
      case _ =>
        log("ignoring " + t.key + " in " + a.id +
          (if (inPath.segments.isEmpty) "" else " for " + inPath))
        EmptyPath
    }
  }

  def build(a: Archive, in: FilePath) {
    targets.foreach { t => t.build(a, path(a, t, in)) }
  }

  override def buildDepsFirst(a: Archive, up: Update, in: FilePath) {
    targets.foreach { t => t.buildDepsFirst(a, up, path(a, t, in)) }
  }

  def update(a: Archive, up: Update, in: FilePath) {
    targets.foreach { t => t.update(a, up, path(a, t, in)) }
  }

  def clean(a: Archive, in: FilePath) {
    targets.reverse.foreach { t => t.clean(a, path(a, t, in)) }
  }
}
