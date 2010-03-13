package xsbt

	import sbt.ComponentManager
	import xsbti.{AnalysisCallback, Logger => xLogger}
	import java.io.File
	import java.net.{URL, URLClassLoader}

/** Interface to the Scala compiler that uses the dependency analysis plugin.  This class uses the Scala library and compiler
* provided by scalaInstance.  This class requires a ComponentManager in order to obtain the interface code to scalac and
* the analysis plugin.  Because these call Scala code for a different Scala version than the one used for this class, they must
* be compiled for the version of Scala being used.*/
class AnalyzingCompiler(val scalaInstance: ScalaInstance, val manager: ComponentManager, log: CompileLogger) extends NotNull
{
	def apply(sources: Set[File], classpath: Set[File], outputDirectory: File, options: Seq[String], callback: AnalysisCallback, maximumErrors: Int, log: CompileLogger): Unit =
		apply(sources, classpath, outputDirectory, options, false, callback, maximumErrors, log)
	def apply(sources: Set[File], classpath: Set[File], outputDirectory: File, options: Seq[String], compilerOnClasspath: Boolean,
		 callback: AnalysisCallback, maximumErrors: Int, log: CompileLogger)
	{
		val arguments = (new CompilerArguments(scalaInstance))(sources, classpath, outputDirectory, options, compilerOnClasspath)
		call("xsbt.CompilerInterface", log)(
			classOf[Array[String]], classOf[AnalysisCallback], classOf[Int], classOf[xLogger] ) (
			arguments.toArray[String] : Array[String], callback, maximumErrors: java.lang.Integer, log )
		// this is commented out because of Scala ticket #2365
		/*
		val interface = getInterfaceClass(interface, log).newInstance.asInstanceOf[AnyRef]
		val runnable = interface.asInstanceOf[{ def run(args: Array[String], callback: AnalysisCallback, maximumErrors: Int, log: xLogger): Unit }]
			// these arguments are safe to pass across the ClassLoader boundary because the types are defined in Java
		//  so they will be binary compatible across all versions of Scala
		runnable.run(arguments.toArray, callback, maximumErrors, log)*/
	}
	def doc(sources: Set[File], classpath: Set[File], outputDirectory: File, options: Seq[String], maximumErrors: Int, log: CompileLogger): Unit =
		doc(sources, classpath, outputDirectory, options, false, maximumErrors, log)
	def doc(sources: Set[File], classpath: Set[File], outputDirectory: File, options: Seq[String], compilerOnClasspath: Boolean,
		 maximumErrors: Int, log: CompileLogger): Unit =
	{
		val arguments = (new CompilerArguments(scalaInstance))(sources, classpath, outputDirectory, options, compilerOnClasspath)
		call("xsbt.ScaladocInterface", log) (classOf[Array[String]], classOf[Int], classOf[xLogger]) (arguments.toArray[String] : Array[String], maximumErrors: java.lang.Integer, log)
	}
	def console(classpath: Set[File], initialCommands: String, log: CompileLogger): Unit =
	{
		val arguments = new CompilerArguments(scalaInstance)
		val classpathString = CompilerArguments.absString(arguments.finishClasspath(classpath, true))
		val bootClasspath = arguments.createBootClasspath
		call("xsbt.ConsoleInterface", log) (classOf[String], classOf[String], classOf[String], classOf[xLogger]) (bootClasspath, classpathString, initialCommands, log)
	}
	def force(log: CompileLogger): Unit = getInterfaceJar(log)
	private def call(interfaceClassName: String, log: CompileLogger)(argTypes: Class[_]*)(args: AnyRef*)
	{
		val interfaceClass = getInterfaceClass(interfaceClassName, log)
		val interface = interfaceClass.newInstance.asInstanceOf[AnyRef]
		val method = interfaceClass.getMethod("run", argTypes : _*)
		try { method.invoke(interface, args: _*) }
		catch { case e: java.lang.reflect.InvocationTargetException => throw e.getCause }
	}
	private[this] def loader =
	{
		val interfaceJar = getInterfaceJar(log)
		val dual = createDualLoader(scalaInstance.loader, getClass.getClassLoader) // this goes to scalaLoader for scala classes and sbtLoader for xsbti classes
		new URLClassLoader(Array(interfaceJar.toURI.toURL), dual)
	}
	private def getInterfaceClass(name: String, log: CompileLogger) = Class.forName(name, true, loader)
	private def getInterfaceJar(log: CompileLogger) =
	{
		// this is the instance used to compile the interface component
		val componentCompiler = newComponentCompiler(log)
		log.debug("Getting " + ComponentCompiler.compilerInterfaceID + " from component compiler for Scala " + scalaInstance.version)
		componentCompiler(ComponentCompiler.compilerInterfaceID)
	}
	def newComponentCompiler(log: CompileLogger) = new ComponentCompiler(new RawCompiler(scalaInstance, log), manager)
	protected def createDualLoader(scalaLoader: ClassLoader, sbtLoader: ClassLoader): ClassLoader =
	{
		val xsbtiFilter = (name: String) => name.startsWith("xsbti.")
		val notXsbtiFilter = (name: String) => !xsbtiFilter(name)
		new DualLoader(scalaLoader, notXsbtiFilter, x => true, sbtLoader, xsbtiFilter, x => false)
	}
	override def toString = "Analyzing compiler (Scala " + scalaInstance.actualVersion + ")"
}