package aQute.bnd.main;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.jar.*;
import java.util.regex.*;
import java.util.zip.*;

import javax.script.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;
import javax.xml.xpath.*;

import org.w3c.dom.*;

import aQute.bnd.build.*;
import aQute.bnd.help.*;
import aQute.bnd.main.BaselineCommands.baseLineOptions;
import aQute.bnd.main.BaselineCommands.schemaOptions;
import aQute.bnd.main.DiffCommand.diffOptions;
import aQute.bnd.main.RepoCommand.repoOptions;
import aQute.bnd.maven.*;
import aQute.bnd.service.action.*;
import aQute.bnd.settings.*;
import aQute.configurable.*;
import aQute.lib.collections.*;
import aQute.lib.filter.*;
import aQute.lib.getopt.*;
import aQute.lib.io.*;
import aQute.lib.justif.*;
import aQute.lib.osgi.*;
import aQute.lib.osgi.Descriptors.TypeRef;
import aQute.lib.osgi.eclipse.*;
import aQute.lib.tag.*;
import aQute.libg.classdump.*;
import aQute.libg.generics.*;
import aQute.libg.header.*;
import aQute.libg.version.*;

/**
 * Utility to make bundles. Should be areplace for jar and much more.
 * 
 * @version $Revision: 1.14 $
 */
public class bnd extends Processor {
	Settings		settings	= new Settings();
	PrintStream		err			= System.err;
	PrintStream		out			= System.out;
	Justif			justif		= new Justif(60);

	static Pattern	JARCOMMANDS	= Pattern
										.compile("(cv?0?(m|M)?f?)|(uv?0?M?f?)|(xv?f?)|(tv?f?)|(i)");

	static Pattern	COMMAND		= Pattern.compile("\\w[\\w\\d]+");

	@Description("OSGi Bundle Tool") interface bndOptions extends Options {
		@Description("Turns errors into warnings so command always succeeds") boolean failok();

		@Description("Report errors pedantically") boolean pedantic();

		@Description("Print out stack traces when there is an unexpected exception") boolean exceptions();

		@Description("Redirect output") File output();

		@Description("Use as base directory") String base();

		@Description("Trace progress") boolean trace();

		@Description("Error/Warning ignore patterns") String[] ignore();

	}

	public static void main(String args[]) throws Exception {
		bnd main = new bnd();
		main.start(args);
	}

	public void start(String args[]) throws Exception {
		CommandLine cl = new CommandLine(this);
		String help = cl.execute(this, "bnd", new ExtList<String>(args));
		check();
		if (help != null)
			err.println(help);
	}

	/**
	 * Rewrite the command line to mimic the jar command
	 * 
	 * @param args
	 * @throws Exception
	 */
	private void rewrite(List<String> args) throws Exception {
		if (args.isEmpty())
			return;

		String arg = args.get(0);
		Matcher m = JARCOMMANDS.matcher(arg);
		if (m.matches()) {
			rewriteJarCmd(args);
			return;
		}

		Project project = getProject();
		if (project != null) {
			Action a = project.getActions().get(arg);
			if (a != null) {
				args.add(0, "project");
			}
		}

		m = COMMAND.matcher(args.get(0));
		if (!m.matches()) {
			args.add(0, "do");
		}

	}

	private void rewriteJarCmd(List<String> args) {
		String jarcmd = args.remove(0);

		char cmd = jarcmd.charAt(0);
		switch (cmd) {
		case 'c':
			args.add(0, "create");
			break;

		case 'u':
			args.add(0, "update");
			break;

		case 'x':
			args.add(0, "extract");
			break;

		case 't':
			args.add(0, "type");
			break;

		case 'i':
			args.add(0, "index");
			break;
		}
		int start = 1;
		for (int i = 1; i < jarcmd.length(); i++) {
			switch (jarcmd.charAt(i)) {
			case 'v':
				args.add(start++, "--verbose");
				break;

			case '0':
				args.add(start++, "--nocompression");
				break;

			case 'm':
				args.add(start++, "--manifest");
				start++; // make the manifest file the parameter
				break;

			case 'M':
				args.add(start++, "--nomanifest");
				break;

			case 'f':
				args.add(start++, "--file");
				break;
			}
		}
	}

	/**
	 * Main command. This has options the bnd base options and will then run
	 * another command.
	 * 
	 * @param options
	 * @throws Exception
	 */
	public void _bnd(bndOptions options) throws Exception {
		try {
			set(FAIL_OK, options.failok() + "");
			setExceptions(options.exceptions());
			setTrace(options.trace());
			setPedantic(options.pedantic());

			if (options.base() != null)
				setBase(getFile(getBase(), options.base()));

			// And the properties
			for (Entry<String, String> entry : options._properties().entrySet()) {
				setProperty(entry.getKey(), entry.getValue());
			}

			CommandLine handler = options._command();
			List<String> arguments = options._();

			// Rewrite command line to match jar commands and
			// handle commands that provide file names

			rewrite(arguments);

			trace("rewritten %s", arguments);

			if (arguments.isEmpty()) {
				Formatter f = new Formatter(err);
				handler.help(f, this);
				f.flush();
			} else {
				String cmd = arguments.remove(0);
				String help = handler.execute(this, cmd, arguments);
				if (help != null) {
					err.println(help);
				}
			}
		} catch (Throwable t) {
			error("Failed %s", t, t.getMessage());
		}

		if (!check(options.ignore())) {
			System.err.flush();
			System.err.flush();
			Thread.sleep(1000);
			System.exit(getErrors().size());
		}
	}

	/**
	 * Options for the jar create command.
	 * 
	 */
	@Description("Equivalent jar command c[v0mf] command (supports the jar tool's syntax). Will wrap the bundle unless --wrapnot is specified") interface createOptions
			extends Options {
		@Description("Verbose (v option)") boolean verbose();

		@Description("No compression (0 option)") boolean nocompression();

		@Description("No manifest (M option)") boolean Manifest();

		@Description("Use manifest (m option)") String manifest();

		@Description("Jar file (f option)") String file();

		@Description("Directory (-C option)") String Cdir();

		@Description("Wrap") boolean wrap();

		@Description("Properties for wrapping") String properties();

		@Description("Bundle Symbolic Name for wrap") String bsn();

		@Description("Bundle Version for wrap") Version version();

		@Description("Force write event if there are errors") boolean force();
	}

	/**
	 * Create jar file
	 * 
	 * <pre>
	 *     jar c[v0M]f jarfile [-C dir] inputfiles [-Joption] 
	 *     jar c[v0]mf manifest jarfile [-C dir] inputfiles [-Joption] 
	 *     jar c[v0M] [-C dir] inputfiles [-Joption] 
	 *     jar c[v0]m manifest [-C dir] inputfiles [-Joption]
	 * </pre>
	 * 
	 * @param options
	 * @throws Exception
	 */
	public void _create(createOptions options) throws Exception {
		Jar jar = new Jar("dot");

		File dir = getBase().getAbsoluteFile();
		String sdir = options.Cdir();
		if (sdir != null)
			dir = getFile(sdir);

		if (options._().isEmpty())
			add(jar, dir, ".", options.verbose());
		else
			for (String f : options._()) {
				f = f.replace(File.separatorChar, '/');
				add(jar, dir, f, options.verbose());
			}

		String manifest = options.manifest();
		if (manifest != null) {
			if (options.verbose())
				err.printf("Adding manifest from %s\n", manifest);

			jar.setManifest(getFile(manifest));
		}

		if (options.Manifest()) {
			jar.setManifest((Manifest) null);
		} else {
			if (options.wrap()) {
				Analyzer w = new Analyzer(this);
				addClose(w);
				w.setBase(getBase());
				w.use(this);
				w.setDefaults(options.bsn(), options.version());
				w.calcManifest();
				getInfo(w);
				w.setJar((Jar) null);
				w.close();
			}
		}

		if (options.nocompression())
			jar.setCompression(Jar.Compression.STORE);

		if (isOk()) {
			String jarFile = options.file();
			if (jarFile == null)
				jar.write(System.out);
			else
				jar.write(jarFile);
		}
		jar.close();

	}

	/**
	 * Helper for the jar create function, adds files to the jar
	 * 
	 * @param jar
	 * @param base
	 * @param path
	 * @param report
	 */
	private void add(Jar jar, File base, String path, boolean report) {
		if (path.endsWith("/"))
			path = path.substring(0, path.length() - 1);

		File f;
		if (path.equals("."))
			f = base;
		else
			f = getFile(base, path);

		err.printf("Adding: %s\n", path);

		if (f.isFile()) {
			jar.putResource(path, new FileResource(f));
		} else if (f.isDirectory()) {
			if (path.equals("."))
				path = "";
			else
				path += "/";

			String[] subs = f.list();
			for (String sub : subs) {

				add(jar, base, path + sub, report);
			}
		}
	}

	/**
	 * Extract a file from the JAR
	 */

	@Description("Extract files from a JAR file, equivalent jar command x[vf] (syntax supported)") interface extractOptions
			extends Options {
		@Description("Verbose (v option)") boolean verbose();

		@Description("Jar file (f option)") String file();

		@Description("Directory where to store") String CDir();
	}

	public void _extract(extractOptions opts) throws Exception {
		Jar jar;

		if (opts.file() != null) {
			File f = getFile(opts.file());
			if (!f.isFile()) {
				error("No such file %s", f);
				return;
			}
			jar = new Jar(f);
		} else {
			jar = new Jar("cin", System.in);
		}
		try {
			Instructions instructions = new Instructions(opts._());
			Collection<String> selected = instructions.select(jar.getResources().keySet(), true);
			File store = getBase();
			if (opts.CDir() != null)
				store = getFile(opts.CDir());

			store.mkdirs();
			Jar.Compression compression = jar.hasCompression();
			for (String path : selected) {
				if (opts.verbose())
					System.err.printf("%8s: %s\n", compression.toString().toLowerCase(), path);

				File f = getFile(store, path);
				f.getParentFile().mkdirs();
				Resource r = jar.getResource(path);
				IO.copy(r.openInputStream(), f);
			}
		} finally {
			jar.close();
		}
	}

	/**
	 * List the contents of the JAR
	 */

	@Description("List files int a JAR file, equivalent jar command t[vf] (syntax supported)") interface typeOptions
			extends Options {
		@Description("Verbose (v option)") boolean verbose();

		@Description("Jar file (f option)") String file();

	}

	public void _type(typeOptions opts) throws Exception {
		Jar jar;

		if (opts.file() != null) {
			File f = getFile(opts.file());
			if (!f.isFile()) {
				error("No such file %s", f);
				return;
			}
			jar = new Jar(f);
		} else {
			jar = new Jar("cin", System.in);
		}

		try {
			Instructions instructions = new Instructions(opts._());
			Collection<String> selected = instructions.select(jar.getResources().keySet(), true);

			for (String path : selected) {
				if (opts.verbose()) {
					Resource r = jar.getResource(path);
					err.printf("%8s %-32s %s\n", r.size(), new Date(r.lastModified()), path);
				} else
					err.printf("%s\n", path);
			}
		} finally {
			jar.close();
		}
	}

	/**
	 * The do command interprets files and does a default action for each file
	 * 
	 * @param project
	 * @param args
	 * @param i
	 * @return
	 * @throws Exception
	 */

	@Description("Execute a file based on its extension. Supported extensions are: bnd (build), bndrun (run), and jar (print)") interface dooptions
			extends Options {
		@Description("The output file") String output();

		@Description("Force even when there are errors") boolean force();
	}

	public void _do(dooptions options) throws Exception {
		for (String path : options._()) {
			if (path.endsWith(Constants.DEFAULT_BND_EXTENSION)) {
				Builder b = new Builder();
				File f = getFile(path);
				b.setProperties(f);
				b.build();

				File out = b.getOutputFile(options.output());
				getInfo(b, f.getName());
				if (isOk()) {
					b.save(out, options.force());
				}
				b.close();
			} else if (path.endsWith(Constants.DEFAULT_JAR_EXTENSION)
					|| path.endsWith(Constants.DEFAULT_BAR_EXTENSION)) {
				File file = getFile(path);
				doPrint(file, MANIFEST);
			} else if (path.endsWith(Constants.DEFAULT_BNDRUN_EXTENSION))
				doRun(path);
			else
				error("Unrecognized file type %s", path);
		}
	}

	/**
	 * Project command, executes actions.
	 */

	interface projectOptions extends Options {
		String project();

		boolean info();
	}

	public void _project(projectOptions options) throws Exception {
		Project project = getProject(options.project());
		if (project == null) {
			error("No project available");
			return;
		}

		List<String> l = new ArrayList<String>(options._());
		if (l.isEmpty()) {
			err.printf("Name         %s\n", project.getName());
			err.printf("Actions      %s\n", project.getActions().keySet());
			err.printf("Directory    %s\n", project.getBase());
			err.printf("Depends on   %s\n", project.getDependson());
			err.printf("Sub builders %s\n", project.getSubBuilders());
			return;
		}

		String cmd = null;
		String arg = null;

		if (!l.isEmpty())
			cmd = l.remove(0);
		if (!l.isEmpty())
			arg = l.remove(0);

		if (!l.isEmpty()) {
			error("Extra arguments %s", options._());
			return;
		}

		if (cmd == null) {
			error("No cmd for project");
			return;
		}

		Action a = project.getActions().get(cmd);
		if (a != null) {
			a.execute(project, arg);
			getInfo(project);
			return;
		}
	}

	/**
	 * Bump a version number
	 * 
	 * @param args
	 * @param i
	 * @throws Exception
	 */
	interface bumpoptions extends Options {
		String project();
	}

	public void _bump(bumpoptions options) throws Exception {
		Project project = getProject(options.project());

		if (project == null) {
			error("No project found, use -base <dir> bump");
			return;
		}

		String mask = null;
		if (!options._().isEmpty()) {
			mask = options._().get(0);
			if (mask.equalsIgnoreCase("major"))
				mask = "+00";
			else if (mask.equalsIgnoreCase("minor"))
				mask = "=+0";
			else if (mask.equalsIgnoreCase("micro"))
				mask = "==+";
			else if (!mask.matches("[+=0]{1,3}")) {
				error("Invalid mask for version bump %s, is (minor|major|micro|<mask>), see $version for mask",
						mask);
				return;
			}
		}

		if (mask == null)
			project.bump();
		else
			project.bump(mask);

		getInfo(project);
		err.println(project.getProperty(BUNDLE_VERSION, "No version found"));
	}

	interface buildoptions extends Options {
		String project();

		boolean test();
	}

	public void _build(buildoptions opts) throws Exception {
		Project project = getProject(opts.project());
		if (project == null) {
			error("No project available");
			return;
		}
		project.build(opts.test());
	}

	interface testOptions extends Options {
		String project();
	}

	public void _test(testOptions opts) throws Exception {
		Project project = getProject(opts.project());
		if (project == null) {
			error("No project available");
			return;
		}
		project.test();
	}

	interface runOptions extends Options {
		String project();
	}

	public void _run(runOptions opts) throws Exception {
		Project project = getProject(opts.project());
		if (project == null) {
			error("No project available");
			return;
		}
		project.run();
	}

	interface cleanOptions extends Options {
		String project();
	}

	public void _clean(cleanOptions opts) throws Exception {
		Project project = getProject(opts.project());
		if (project == null) {
			error("No project available");
			return;
		}
		project.clean();
	}

	@Arguments(arg = { "header|instruction", "..." }) interface syntaxOptions extends Options {
	}

	public void _syntax(syntaxOptions opts) throws Exception {
		List<String> args = opts._();
		StringBuilder sb = new StringBuilder();
		Formatter f = new Formatter(sb);

		for (String s : args) {
			f.format("[%s]\n", s);
			Syntax sx = Syntax.HELP.get(s);
			if (s == null)
				f.format("Unknown");
			else {
				print(f, sx, "  ");
			}
		}
		f.format("\n");
		f.flush();
		justif.wrap(sb);
		err.println(sb);
	}

	private void print(Formatter f, Syntax sx, String indent) {
		if (sx == null)
			return;

		f.format("%s%s\n\n", indent, sx.getLead());
		if (sx.getValues() != null)
			f.format("%sValues\t3: %s\n", indent, sx.getValues());

		if (sx.getPattern() != null)
			f.format("%sPattern  \t3: %s\n", indent, sx.getPattern());
		if (sx.getExample() != null)
			f.format("%sExample  \t3: %s\n", indent, sx.getExample());
		if (sx.getChildren() != null) {

			for (Syntax child : sx.getChildren()) {
				f.format("\n%s[%s]\n", indent, child.getHeader());
				print(f, child, indent + "  ");
			}
		}
	}

	private void doRun(String path) throws Exception {
		File file = getFile(path);
		if (!file.isFile())
			throw new FileNotFoundException(path);

		File projectDir = file.getParentFile();
		File workspaceDir = projectDir.getParentFile();
		if (workspaceDir == null) {
			workspaceDir = new File(System.getProperty("user.home") + File.separator + ".bnd");
		}
		Workspace ws = Workspace.getWorkspace(workspaceDir);

		File bndbnd = new File(projectDir, Project.BNDFILE);
		Project project;
		if (bndbnd.isFile()) {
			project = new Project(ws, projectDir, bndbnd);
			project.doIncludeFile(file, true, project.getProperties());
		} else
			project = new Project(ws, projectDir, file);

		project.setTrace(isTrace());
		project.setPedantic(isPedantic());
		try {
			project.run();

		} catch (Exception e) {
			error("Failed to run %s: %s", project, e);
		}
		getInfo(project);
	}

	/**
	 * List all deliverables for this workspace.
	 * 
	 */
	interface deliverableOptions extends Options {
		String project();

		boolean limit();
	}

	public void _deliverables(deliverableOptions options) throws Exception {
		Project project = getProject(options.project());
		if (project == null) {
			error("No project");
			return;
		}

		long start = System.currentTimeMillis();
		Collection<Project> projects;
		if (options.limit())
			projects = Arrays.asList(project);
		else
			projects = project.getWorkspace().getAllProjects();

		List<Container> containers = new ArrayList<Container>();

		for (Project p : projects) {
			containers.addAll(p.getDeliverables());
		}
		long duration = System.currentTimeMillis() - start;
		err.println("Took " + duration + " ms");

		for (Container c : containers) {
			Version v = new Version(c.getVersion());
			err.printf("%-40s %8s  %s\n", c.getBundleSymbolicName(), v.getWithoutQualifier(),
					c.getFile());
		}
		getInfo(project);
	}

	/**
	 * Show the value of a macro
	 * 
	 * @param args
	 * @param i
	 * @return
	 * @throws Exception
	 */
	interface macroOptions extends Options {
		String project();
	}

	public void _macro(macroOptions options) throws Exception {
		Project project = getProject(options.project());

		if (project == null) {

		}

		StringBuilder sb = new StringBuilder();
		Macro r = project.getReplacer();
		getInfo(project);

		String del = "";
		for (String s : options._()) {
			if (!s.startsWith("${")) {
				s = "${" + s;
			}
			if (!s.endsWith("}")) {
				s += "}";
			}
			s = s.replace(':', ';');
			String p = r.process(s);
			sb.append(del);
			sb.append(p);
			del = " ";
		}
		getInfo(project);
		err.println(sb);
	}

	/**
	 * Release the project
	 * 
	 * @param args
	 * @param i
	 * @throws Exception
	 */
	interface releaseOptions extends Options {
		String project();

		boolean test();
	}

	public void _release(releaseOptions options) throws Exception {
		Project project = getProject(options.project());
		if (project == null)
			return;

		project.release(options.test());
		getInfo(project);
	}

	/**
	 * Cross reference every class in the jar file to the files it references
	 * 
	 * @param args
	 * @param i
	 */

	interface xrefOptions extends Options {
	}

	public void _xref(xrefOptions options) {
		Analyzer analyzer = new Analyzer();
		MultiMap<TypeRef, TypeRef> table = new MultiMap<TypeRef, TypeRef>();
		Set<TypeRef> set = Create.set();

		for (String arg : options._()) {
			try {
				File file = new File(arg);
				Jar jar = new Jar(file.getName(), file);
				try {
					for (Map.Entry<String, Resource> entry : jar.getResources().entrySet()) {
						String key = entry.getKey();
						Resource r = entry.getValue();
						if (key.endsWith(".class")) {
							TypeRef ref = analyzer.getTypeRefFromPath(key);
							set.add(ref);

							InputStream in = r.openInputStream();
							Clazz clazz = new Clazz(analyzer, key, r);

							// TODO use the proper bcp instead
							// of using the default layout
							Set<TypeRef> s = clazz.parseClassFile();
							table.addAll(ref, s);
							set.addAll(s);
							in.close();
						}
					}
				} finally {
					jar.close();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		SortedList<TypeRef> labels = new SortedList<TypeRef>(table.keySet());
		for (TypeRef element : labels) {
			Iterator<TypeRef> row = table.get(element).iterator();
			String first = "";
			if (row.hasNext())
				first = row.next().getFQN();
			err.printf("%40s > %s\n", element.getFQN(), first);
			while (row.hasNext()) {
				err.printf("%40s   %s\n", "", row.next().getFQN());
			}
		}
	}

	interface eclipseOptions extends Options {
		String dir();
	}

	public void _eclipse(eclipseOptions options) throws Exception {

		File dir = getBase();
		if (options.dir() != null)
			dir = getFile(options.dir());

		if (!dir.isDirectory())
			error("Eclipse requires a path to a directory: " + dir.getAbsolutePath());

		if (options._().size() != 0)
			error("Unnecessary arguments %s", options._());

		if (!isOk())
			return;

		File cp = new File(dir, ".classpath");
		if (!cp.exists()) {
			error("Cannot find .classpath in project directory: " + dir.getAbsolutePath());
		} else {
			EclipseClasspath eclipse = new EclipseClasspath(this, dir.getParentFile(), dir);
			err.println("Classpath    " + eclipse.getClasspath());
			err.println("Dependents   " + eclipse.getDependents());
			err.println("Sourcepath   " + eclipse.getSourcepath());
			err.println("Output       " + eclipse.getOutput());
			err.println();
		}
	}

	/**
	 * Buildx
	 */
	final static int	BUILD_SOURCES	= 1;
	final static int	BUILD_POM		= 2;
	final static int	BUILD_FORCE		= 4;

	interface buildxOptions extends Options {
		String output();

		List<String> classpath();

		List<String> sourcepath();

		boolean eclipse();

		boolean noeclipse();

		boolean sources();

		boolean pom();

		boolean force();
	}

	public void _buildx(buildxOptions options) throws Exception {
		File output = null;
		if (options.output() == null)
			output = getFile(options.output());

		// Create a build order

		List<Builder> builders = new ArrayList<Builder>();
		List<String> order = new ArrayList<String>();
		List<String> active = new ArrayList<String>();

		for (String s : options._()) {
			prebuild(active, order, builders, s);
		}

		for (Builder b : builders) {
			if (options.classpath() != null) {
				for (String f : options.classpath()) {
					b.addClasspath(getFile(f));
				}
			}

			if (options.sourcepath() != null) {
				for (String f : options.sourcepath()) {
					b.addSourcepath(getFile(f));
				}
			}

			if (options.sources())
				b.setSources(true);

			if (options.eclipse()) {
				EclipseClasspath ep = new EclipseClasspath(this, getBase().getParentFile(),
						getBase());

				b.addClasspath(ep.getClasspath());
				b.addClasspath(ep.getBootclasspath());
				b.addSourcepath(ep.getSourcepath());
			}

			Jar jar = b.build();

			File outputFile = b.getOutputFile(options.output());

			if (options.pom()) {
				Resource r = new PomFromManifest(jar.getManifest());
				jar.putResource("pom.xml", r);
				String path = output.getName().replaceAll("\\.jar$", ".pom");
				if (path.equals(output.getName()))
					path = output.getName() + ".pom";
				File pom = new File(output.getParentFile(), path);
				OutputStream out = new FileOutputStream(pom);
				try {
					r.write(out);
				} finally {
					out.close();
				}
			}

			getInfo(b, b.getPropertiesFile().getName());
			if (isOk()) {
				b.save(outputFile, options.force());
			}
			b.close();
		}
	}

	// Find the build order
	// by recursively passing
	// through the builders.
	private void prebuild(List<String> set, List<String> order, List<Builder> builders, String s)
			throws IOException {
		if (order.contains(s)) // Already done
			return;

		if (set.contains(s))
			error("Cyclic -prebuild dependency %s from %s", s, set);

		Builder b = new Builder(this);
		b.setProperties(getFile(s));

		String prebuild = b.get("prebuild");
		if (prebuild != null) {
			set.add(s);
			try {
				Collection<String> parts = split(prebuild);
				for (String p : parts) {
					prebuild(set, order, builders, p);
				}
			} finally {
				set.remove(s);
			}
		}
		order.add(s);
		builders.add(b);
	}

	/**
	 * View files from JARs
	 * 
	 * We parse the commandline and print each file on it.
	 * 
	 * @param args
	 * @param i
	 * @throws Exception
	 */
	interface viewOptions extends Options {
		String charset();
	}

	public void _view(viewOptions options) throws Exception {
		String charset = "UTF-8";
		if (options.charset() != null)
			charset = options.charset();

		if (options._().isEmpty()) {
			error("Need a jarfile as source");
			return;
		}
		List<String> args = options._();
		File file = getFile(args.remove(0));
		if (!file.isFile()) {
			error("File does not exist %s", file);
			return;
		}

		Jar jar = new Jar(file);

		if (args.isEmpty())
			args.add("*");

		Instructions instructions = new Instructions(args);
		Collection<String> selected = instructions.select(jar.getResources().keySet(), true);
		for (String selection : selected) {
			Resource r = jar.getResource(selection);

			if (selection.endsWith(".MF")) {
				Manifest m = new Manifest(r.openInputStream());
				printManifest(m);
			} else if (selection.endsWith(".class")) {
				ClassDumper clsd = new ClassDumper(selection, r.openInputStream());
				clsd.dump(err);
			} else {
				InputStreamReader isr = new InputStreamReader(r.openInputStream(), charset);
				IO.copy(isr, err);
			}
		}
	}

	/**
	 * Wrap a jar to a bundle.
	 * 
	 * @param args
	 * @param i
	 * @throws Exception
	 */
	interface wrapOptions extends Options {
		String output();

		String properties();

		List<String> classpath();

		boolean force();

		String bsn();

		Version version();
	}

	public void _wrap(wrapOptions options) throws Exception {
		List<File> classpath = Create.list();
		File properties = getBase();

		if (options.properties() != null) {
			properties = getFile(options.properties());
		}

		for (String cp : options.classpath()) {
			classpath.add(getFile(cp));
		}

		for (String j : options._()) {
			File file = getFile(j);
			if (!file.isFile()) {
				error("File does not exist %s", file);
				continue;
			}

			Analyzer wrapper = new Analyzer(this);
			try {
				wrapper.use(this);
				addClose(wrapper);

				wrapper.setJar(file);
				if (options.bsn() != null)
					wrapper.setBundleSymbolicName(options.bsn());

				if (options.version() != null)
					wrapper.setBundleVersion(options.version());

				File outputFile = wrapper.getOutputFile(options.output());

				File p = properties;
				if (p.isDirectory()) {
					p = getFile(p, file.getName());
				}
				if (p.isFile())
					wrapper.setProperties(p);

				wrapper.calcManifest();

				if (wrapper.isOk()) {
					wrapper.save(outputFile, options.force());
				}
			} finally {
				wrapper.close();
			}
		}
	}

	/**
	 * Printout all the variables in scope.
	 * 
	 * @param args
	 * @param i
	 * @throws Exception
	 */
	interface debugOptions extends Options {
		String project();
	}

	public void _debug(debugOptions options) throws Exception {
		Processor project = getProject(options.project());
		if (project == null)
			project = this;

		MultiMap<String, String> table = new MultiMap<String, String>();

		for (Iterator<String> i = project.iterator(); i.hasNext();) {
			String key = i.next();
			String s = project.get(key);
			Collection<String> set = split(s);
			table.addAll(key, set);
		}
		printMultiMap(table);
	}

	/**
	 * Manage the repo.
	 * 
	 * <pre>
	 * out.println(&quot; bnd repo [--repo|-r ('maven'| &lt;dir&gt;)]*&quot;);
	 * out.println(&quot;        repos                          # list the repositories&quot;);
	 * out.println(&quot;        list                           # list all content (not always possible)&quot;);
	 * out.println(&quot;        get &lt;bsn&gt; &lt;version&gt; &lt;file&gt;?    # get an artifact&quot;);
	 * out.println(&quot;        put &lt;file&gt;+                    # put in artifacts&quot;);
	 * out.println(&quot;        help&quot;);
	 * </pre>
	 */

	public void _repo(repoOptions opts) throws Exception {
		new RepoCommand(this, opts);
	}

	/**
	 * Run a JavaScript program
	 * 
	 */
	interface scriptOptions extends Options {
		String file();
	}

	public void _script(scriptOptions opts) throws IOException, ScriptException {
		new ScriptCommand(this, opts);
	}

	/**
	 * Print out a JAR
	 */

	final static int	VERIFY		= 1;

	final static int	MANIFEST	= 2;

	final static int	LIST		= 4;

	final static int	ECLIPSE		= 8;
	final static int	IMPEXP		= 16;
	final static int	USES		= 32;
	final static int	USEDBY		= 64;
	final static int	COMPONENT	= 128;
	final static int	METATYPE	= 256;

	static final int	HEX			= 0;

	interface printOptions extends Options {
		boolean verify();

		boolean manifest();

		boolean list();

		boolean eclipse();

		boolean impexp();

		boolean uses();

		boolean by();

		boolean component();

		boolean typemeta();

		boolean hex();
	}

	public void _print(printOptions options) throws Exception {
		for (String s : options._()) {
			File file = getFile(s);
			if (!file.isFile()) {
				error("File %s does not exist", file);
				continue;
			}
			int opts = 0;
			if (options.verify())
				opts |= VERIFY;

			if (options.manifest())
				opts |= MANIFEST;

			if (options.list())
				opts |= LIST;

			if (options.eclipse())
				opts |= ECLIPSE;

			if (options.impexp())
				opts |= IMPEXP;

			if (options.uses())
				opts |= USES;

			if (options.by())
				opts |= USEDBY;

			if (options.component())
				opts |= COMPONENT;

			if (options.typemeta())
				opts |= METATYPE;

			if (opts == 0)
				opts = MANIFEST;

			doPrint(file, opts);
		}
	}

	private void doPrint(File file, int options) throws ZipException, IOException, Exception {

		Jar jar = new Jar(file.getName(), file);
		try {
			if ((options & VERIFY) != 0) {
				Verifier verifier = new Verifier(jar);
				verifier.setPedantic(isPedantic());
				verifier.verify();
				getInfo(verifier);
			}
			if ((options & MANIFEST) != 0) {
				Manifest manifest = jar.getManifest();
				if (manifest == null)
					warning("JAR has no manifest " + file);
				else {
					err.println("[MANIFEST " + jar.getName() + "]");
					printManifest(manifest);
				}
				err.println();
			}
			if ((options & IMPEXP) != 0) {
				err.println("[IMPEXP]");
				Manifest m = jar.getManifest();
				Domain domain = Domain.domain(m);

				if (m != null) {
					Parameters imports = domain.getImportPackage();
					Parameters exports = domain.getExportPackage();
					for (String p : exports.keySet()) {
						if (imports.containsKey(p)) {
							Attrs attrs = imports.get(p);
							if (attrs.containsKey(VERSION_ATTRIBUTE)) {
								exports.get(p).put("imported-as", attrs.get(VERSION_ATTRIBUTE));
							}
						}
					}
					print("Import-Package", new TreeMap<String, Attrs>(imports));
					print("Export-Package", new TreeMap<String, Attrs>(exports));
				} else
					warning("File has no manifest");
			}

			if ((options & (USES | USEDBY)) != 0) {
				err.println();
				Analyzer analyzer = new Analyzer();
				analyzer.setPedantic(isPedantic());
				analyzer.setJar(jar);
				analyzer.analyze();
				if ((options & USES) != 0) {
					err.println("[USES]");
					printMultiMap(analyzer.getUses());
					err.println();
				}
				if ((options & USEDBY) != 0) {
					err.println("[USEDBY]");
					printMultiMap(analyzer.getUses().transpose());
				}
			}

			if ((options & COMPONENT) != 0) {
				printComponents(err, jar);
			}

			if ((options & METATYPE) != 0) {
				printMetatype(err, jar);
			}

			if ((options & LIST) != 0) {
				err.println("[LIST]");
				for (Map.Entry<String, Map<String, Resource>> entry : jar.getDirectories()
						.entrySet()) {
					String name = entry.getKey();
					Map<String, Resource> contents = entry.getValue();
					err.println(name);
					if (contents != null) {
						for (String element : contents.keySet()) {
							int n = element.lastIndexOf('/');
							if (n > 0)
								element = element.substring(n + 1);
							err.print("  ");
							err.print(element);
							String path = element;
							if (name.length() != 0)
								path = name + "/" + element;
							Resource r = contents.get(path);
							if (r != null) {
								String extra = r.getExtra();
								if (extra != null) {

									err.print(" extra='" + escapeUnicode(extra) + "'");
								}
							}
							err.println();
						}
					} else {
						err.println(name + " <no contents>");
					}
				}
				err.println();
			}
		} finally {
			jar.close();
		}
	}

	/**
	 * @param manifest
	 */
	void printManifest(Manifest manifest) {
		SortedSet<String> sorted = new TreeSet<String>();
		for (Object element : manifest.getMainAttributes().keySet()) {
			sorted.add(element.toString());
		}
		for (String key : sorted) {
			Object value = manifest.getMainAttributes().getValue(key);
			format("%-40s %-40s\r\n", new Object[] { key, value });
		}
	}

	private final char nibble(int i) {
		return "0123456789ABCDEF".charAt(i & 0xF);
	}

	private final String escapeUnicode(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= ' ' && c <= '~' && c != '\\')
				sb.append(c);
			else {
				sb.append("\\u");
				sb.append(nibble(c >> 12));
				sb.append(nibble(c >> 8));
				sb.append(nibble(c >> 4));
				sb.append(nibble(c));
			}
		}
		return sb.toString();
	}

	/**
	 * Print the components in this JAR.
	 * 
	 * @param jar
	 */
	private void printComponents(PrintStream out, Jar jar) throws Exception {
		out.println("[COMPONENTS]");
		Manifest manifest = jar.getManifest();
		if (manifest == null) {
			out.println("No manifest");
			return;
		}

		String componentHeader = manifest.getMainAttributes().getValue(Constants.SERVICE_COMPONENT);
		Parameters clauses = new Parameters(componentHeader);
		for (String path : clauses.keySet()) {
			out.println(path);

			Resource r = jar.getResource(path);
			if (r != null) {
				InputStreamReader ir = new InputStreamReader(r.openInputStream(),
						Constants.DEFAULT_CHARSET);
				OutputStreamWriter or = new OutputStreamWriter(out, Constants.DEFAULT_CHARSET);
				try {
					IO.copy(ir, or);
				} finally {
					or.flush();
					ir.close();
				}
			} else {
				out.println("  - no resource");
				warning("No Resource found for service component: " + path);
			}
		}
		out.println();
	}

	/**
	 * Print the metatypes in this JAR.
	 * 
	 * @param jar
	 */
	private void printMetatype(PrintStream out, Jar jar) throws Exception {
		out.println("[METATYPE]");
		Manifest manifest = jar.getManifest();
		if (manifest == null) {
			out.println("No manifest");
			return;
		}

		Map<String, Resource> map = jar.getDirectories().get("OSGI-INF/metatype");
		if (map != null) {
			for (Map.Entry<String, Resource> entry : map.entrySet()) {
				out.println(entry.getKey());
				IO.copy(entry.getValue().openInputStream(), out);
				out.println();
			}
			out.println();
		}
	}

	<T extends Comparable<?>> void printMultiMap(Map<T, ? extends Collection<T>> map) {
		SortedList keys = new SortedList<Object>(map.keySet());
		for (Object key : keys) {
			String name = key.toString();

			SortedList<Object> values = new SortedList<Object>(map.get(key));
			String list = vertical(40, values);
			format("%-40s %s", name, list);
		}
	}

	String vertical(int padding, Collection<?> used) {
		StringBuilder sb = new StringBuilder();
		String del = "";
		for (Object s : used) {
			String name = s.toString();
			sb.append(del);
			sb.append(name);
			sb.append("\r\n");
			del = pad(padding);
		}
		if (sb.length() == 0)
			sb.append("\r\n");
		return sb.toString();
	}

	String pad(int i) {
		StringBuilder sb = new StringBuilder();
		while (i-- > 0)
			sb.append(' ');
		return sb.toString();
	}

	/**
	 * 
	 * @param msg
	 * @param ports
	 */

	private void print(String msg, Map<?, ? extends Map<?, ?>> ports) {
		if (ports.isEmpty())
			return;
		err.println(msg);
		for (Entry<?, ? extends Map<?, ?>> entry : ports.entrySet()) {
			Object key = entry.getKey();
			Map<?, ?> clause = Create.copy(entry.getValue());
			clause.remove("uses:");
			format("  %-38s %s\r\n", key.toString().trim(),
					clause.isEmpty() ? "" : clause.toString());
		}
	}

	private void format(String string, Object... objects) {
		if (objects == null || objects.length == 0)
			return;

		StringBuilder sb = new StringBuilder();
		int index = 0;
		for (int i = 0; i < string.length(); i++) {
			char c = string.charAt(i);
			switch (c) {
			case '%':
				String s = objects[index++] + "";
				int width = 0;
				int justify = -1;

				i++;

				c = string.charAt(i++);
				switch (c) {
				case '-':
					justify = -1;
					break;
				case '+':
					justify = 1;
					break;
				case '|':
					justify = 0;
					break;
				default:
					--i;
				}
				c = string.charAt(i++);
				while (c >= '0' && c <= '9') {
					width *= 10;
					width += c - '0';
					c = string.charAt(i++);
				}
				if (c != 's') {
					throw new IllegalArgumentException("Invalid sprintf format:  " + string);
				}

				if (s.length() > width)
					sb.append(s);
				else {
					switch (justify) {
					case -1:
						sb.append(s);
						for (int j = 0; j < width - s.length(); j++)
							sb.append(" ");
						break;

					case 1:
						for (int j = 0; j < width - s.length(); j++)
							sb.append(" ");
						sb.append(s);
						break;

					case 0:
						int spaces = (width - s.length()) / 2;
						for (int j = 0; j < spaces; j++)
							sb.append(" ");
						sb.append(s);
						for (int j = 0; j < width - s.length() - spaces; j++)
							sb.append(" ");
						break;
					}
				}
				break;

			default:
				sb.append(c);
			}
		}
		err.print(sb);
	}

	public void setOut(PrintStream out) {
		this.err = out;
	}

	/**
	 * Patch
	 */

	interface patchOptions extends Options {

	}

	public void patch(patchOptions opts) throws Exception {
		PatchCommand pcmd = new PatchCommand(this);
		List<String> args = opts._();
		opts._command().execute(pcmd, args.remove(0), args);
	}

	/**
	 * Run the tests from a prepared bnd file.
	 * 
	 * @param args
	 * @param i
	 * @throws Exception
	 */

	@Description("Run OSGi tests and create report") interface runtestsOptions extends Options {
		String reportdir();

		String title();

		String dir();

		String workspace();
	}

	public void _runtests(runtestsOptions opts) throws Exception {
		int errors = 0;
		File cwd = new File("").getAbsoluteFile();

		Workspace ws = new Workspace(cwd);
		File reportDir = getFile("reports");

		IO.delete(reportDir);

		Tag summary = new Tag("summary");
		summary.addAttribute("date", new Date());
		summary.addAttribute("ws", ws.getBase());

		if (opts.reportdir() != null) {
			reportDir = getFile(opts.reportdir());
		}
		reportDir.mkdirs();

		if (!reportDir.isDirectory())
			error("reportdir must be a directory %s (tried to create it ...)", reportDir);

		if (opts.title() != null)
			summary.addAttribute("title", opts.title());

		if (opts.dir() != null)
			cwd = getFile(opts.dir());

		if (opts.workspace() != null)
			ws = Workspace.getWorkspace(getFile(opts.workspace()));

		// TODO check all the arguments

		boolean hadOne = false;
		try {
			for (String arg : opts._()) {
				File f = getFile(arg);
				errors += runtTest(f, ws, reportDir, summary);
				hadOne = true;
			}

			if (!hadOne) {
				// See if we had any, if so, just use all files in
				// the current directory
				File[] files = cwd.listFiles();
				for (File f : files) {
					if (f.getName().endsWith(".bnd")) {
						errors += runtTest(f, ws, reportDir, summary);
					}
				}
			}
		} catch (Throwable e) {
			if (isExceptions())
				e.printStackTrace();

			error("FAILURE IN RUNTESTS", e);
			errors++;
		}

		if (errors > 0)
			summary.addAttribute("errors", errors);

		for (String error : getErrors()) {
			Tag e = new Tag("error");
			e.addContent(error);
		}

		for (String warning : getWarnings()) {
			Tag e = new Tag("warning");
			e.addContent(warning);
		}

		File r = getFile(reportDir, "summary.xml");
		FileOutputStream out = new FileOutputStream(r);
		PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, "UTF-8"));

		try {
			summary.print(0, pw);
		} finally {
			pw.close();
			out.close();
		}
		if (errors != 0)
			error("Errors found %s", errors);
	}

	/**
	 * Help function to run the tests
	 */
	private int runtTest(File testFile, Workspace ws, File reportDir, Tag summary) throws Exception {
		File tmpDir = new File(reportDir, "tmp");
		tmpDir.mkdirs();
		tmpDir.deleteOnExit();

		Tag test = new Tag(summary, "test");
		test.addAttribute("path", testFile.getAbsolutePath());
		if (!testFile.isFile()) {
			error("No bnd file: %s", testFile);
			test.addAttribute("exception", "No bnd file found");
			throw new FileNotFoundException("No bnd file found for " + testFile.getAbsolutePath());
		}

		Project project = new Project(ws, testFile.getAbsoluteFile().getParentFile(),
				testFile.getAbsoluteFile());
		project.setTrace(isTrace());
		project.setProperty(NOBUNDLES, "true");

		ProjectTester tester = project.getProjectTester();

		getInfo(project, project.toString() + ": ");

		if (!isOk())
			throw new IllegalStateException("Errors found while creating the bnd test project "
					+ testFile.getAbsolutePath());

		tester.setContinuous(false);
		tester.setReportDir(tmpDir);
		test.addAttribute("title", project.toString());
		long start = System.currentTimeMillis();
		try {
			int errors = tester.test();

			Collection<File> reports = tester.getReports();
			for (File report : reports) {
				Tag bundle = new Tag(test, "bundle");
				File dest = new File(reportDir, report.getName());
				report.renameTo(dest);
				bundle.addAttribute("file", dest.getAbsolutePath());
				doPerReport(bundle, dest);
			}

			switch (errors) {
			case ProjectLauncher.OK:
				return 0;

			case ProjectLauncher.CANCELED:
				test.addAttribute("failed", "canceled");
				return 1;

			case ProjectLauncher.DUPLICATE_BUNDLE:
				test.addAttribute("failed", "duplicate bundle");
				return 1;

			case ProjectLauncher.ERROR:
				test.addAttribute("failed", "unknown reason");
				return 1;

			case ProjectLauncher.RESOLVE_ERROR:
				test.addAttribute("failed", "resolve error");
				return 1;

			case ProjectLauncher.TIMEDOUT:
				test.addAttribute("failed", "timed out");
				return 1;
			case ProjectLauncher.WARNING:
				test.addAttribute("warning", "true");
				return 1;

			case ProjectLauncher.ACTIVATOR_ERROR:
				test.addAttribute("failed", "activator error");
				return 1;

			default:
				if (errors > 0) {
					test.addAttribute("errors", errors);
					return errors;
				} else {
					test.addAttribute("failed", "unknown reason");
					return 1;
				}
			}
		} catch (Exception e) {
			test.addAttribute("failed", e);
			throw e;
		} finally {
			long duration = System.currentTimeMillis() - start;
			test.addAttribute("duration", (duration + 500) / 1000);
			getInfo(project, project.toString() + ": ");
		}
	}

	/**
	 * Calculate the coverage if there is coverage info in the test file.
	 */

	private void doPerReport(Tag report, File file) throws Exception {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true); // never forget this!
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(file);

			XPathFactory xpathFactory = XPathFactory.newInstance();
			XPath xpath = xpathFactory.newXPath();

			doCoverage(report, doc, xpath);
			doHtmlReport(report, file, doc, xpath);

		} catch (Exception e) {
			report.addAttribute("coverage-failed", e.getMessage());
		}
	}

	private void doCoverage(Tag report, Document doc, XPath xpath) throws XPathExpressionException {
		int bad = Integer.parseInt(xpath.evaluate("count(//method[count(ref)<2])", doc));
		int all = Integer.parseInt(xpath.evaluate("count(//method)", doc));
		report.addAttribute("coverage-bad", bad);
		report.addAttribute("coverage-all", all);
	}

	private void doHtmlReport(Tag report, File file, Document doc, XPath xpath) throws Exception {
		String path = file.getAbsolutePath();
		if (path.endsWith(".xml"))
			path = path.substring(0, path.length() - 4);
		path += ".html";
		File html = new File(path);
		trace("Creating html report: %s", html);

		TransformerFactory fact = TransformerFactory.newInstance();

		InputStream in = getClass().getResourceAsStream("testreport.xsl");
		if (in == null) {
			warning("Resource not found: test-report.xsl, no html report");
		} else {
			FileWriter out = new FileWriter(html);
			try {
				Transformer transformer = fact.newTransformer(new StreamSource(in));
				transformer.transform(new DOMSource(doc), new StreamResult(out));
				trace("Transformed");
			} finally {
				in.close();
				out.close();
			}
		}
	}

	/**
	 * Merge a bundle with its source.
	 * 
	 * @throws Exception
	 */

	interface verifyOptions extends Options {
	}

	public void _verify(verifyOptions opts) throws Exception {
		for (String path : opts._()) {
			File f = getFile(path);
			if (!f.isFile()) {
				error("No such file: %ss", f);
			} else {
				Jar jar = new Jar(f);
				if (jar.getManifest() == null || jar.getBsn() == null)
					error("Not a bundle %s", f);
				else {
					Verifier v = new Verifier(jar);
					getInfo(v, f.getName());
					v.close();
				}
				jar.close();
			}
		}
	}

	/**
	 * Merge a bundle with its source.
	 * 
	 * @throws Exception
	 */

	@Description("Merge a binary jar with its sources. It is possible to specify  source path")//
	@Arguments(arg = { "jar file/dir", "source file/dir", "..." })//
	interface sourceOptions extends Options {
		@Description("The output file") String output();
	}

	public void _source(sourceOptions opts) throws Exception {

		List<String> arguments = opts._();
		File jarFile = getFile(arguments.remove(0));

		if (!jarFile.exists()) {
			error("File %s does not exist ", jarFile);
			return;
		}

		List<Jar> sources = new ArrayList<Jar>();
		for (String path : arguments) {
			File sourceFile = getFile(path);
			if (!sourceFile.exists()) {
				error("File %s does not exist ", sourceFile);
			} else {
				Jar source = new Jar(sourceFile);
				addClose(source);
				sources.add(source);
			}
		}

		Jar jar = new Jar(jarFile);
		try {
			addClose(jar);
			try {
				// TOOD ignoring bcp

				nextClass: for (String path : jar.getResources().keySet()) {
					if (!path.endsWith(".class") || path.indexOf('$') > 0)
						continue;

					path = replaceExtension(path, ".class", ".java");
					String destPath = "OSGI-INF/src/" + path;

					if (jar.getResources().containsKey(destPath))
						continue;

					for (Jar source : sources) {
						Resource r = source.getResource(path);
						if (r == null)
							r = source.getResource(path);

						if (r == null)
							r = source.getResource("OSGI-OPT/src/" + path);

						if (r == null)
							r = source.getResource("src/" + path);

						if (r != null) {
							jar.putResource("OSGI-OPT/src/" + path, r);
							continue nextClass;

						}
					}
					trace("source not found %s", path);
				}

				if (opts.output() != null) {
					jarFile = getFile(opts.output());
					jarFile.getParentFile().mkdirs();
				}

				if (jarFile.isDirectory())
					jarFile = getFile(jarFile.getAbsolutePath() + ".jar");

				jar.write(jarFile);
				trace("wrote %s", jarFile);
			} finally {
				for (Jar s : sources)
					s.close();
			}
		} finally {
			jar.close();
		}
	}

	/**
	 * Diff two jar files
	 * 
	 * @return
	 * @throws Exception
	 */

	public void _diff(diffOptions opts) throws Exception {
		DiffCommand diff = new DiffCommand(this);
		diff.diff(opts);
	}

	/**
	 * Baseline
	 * 
	 * @return
	 * @throws Exception
	 */

	public void _baseline(baseLineOptions opts) throws Exception {
		BaselineCommands baseliner = new BaselineCommands(this);
		baseliner._baseline(opts);
	}

	/**
	 * Create a schema of package deltas and versions
	 * 
	 * @return
	 * @throws Exception
	 */

	public void _schema(schemaOptions opts) throws Exception {
		BaselineCommands baseliner = new BaselineCommands(this);
		baseliner._schema(opts);
	}

	public Project getProject() throws Exception {
		return getProject(null);
	}

	public Project getProject(String where) throws Exception {
		if (where == null || where.equals("."))
			where = Project.BNDFILE;

		File f = getFile(where);
		if (f.isDirectory()) {
			f = new File(f, Project.BNDFILE);
		}

		if (f.isFile()) {
			File projectDir = f.getParentFile();
			File workspaceDir = projectDir.getParentFile();
			Workspace ws = Workspace.getWorkspace(workspaceDir);
			Project project = ws.getProject(projectDir.getName());
			if (project.isValid()) {
				return project;
			}
		}

		if (where.equals(Project.BNDFILE)) {
			return null;
		} else
			error("Project not found: " + f);

		return null;
	}

	/**
	 * Convert files
	 */
	@Description("Converter to different formats") @Arguments(arg = { "from", "to" }) interface convertOptions
			extends Options {
		@Config(description = "Convert a manifest to a properties files") boolean m2p();

		@Config(description = "Save as xml") boolean xml();
	}

	public void _convert(convertOptions opts) throws IOException {
		File from = getFile(opts._().get(0));
		File to = getFile(opts._().get(1));
		if (opts.m2p()) {
			FileInputStream in = new FileInputStream(from);
			try {
				Properties p = new Properties();
				Manifest m = new Manifest(in);
				Attributes attrs = m.getMainAttributes();
				for (Map.Entry<Object, Object> i : attrs.entrySet()) {
					p.put(i.getKey().toString(), i.getValue().toString());
				}
				FileOutputStream fout = new FileOutputStream(to);
				try {
					if (opts.xml())
						p.storeToXML(fout, "converted from " + from);
					else
						p.store(fout, "converted from " + from);
				} finally {
					fout.close();
				}
			} finally {
				in.close();
			}
			return;
		} else
			error("no conversion specified");
	}

	/**
	 * Create a list of file names that match manifest headers
	 * 
	 * bnd select -h Bundle-SymbolicName --where (...) *
	 */
	@Description("Helps finding information in a set of JARs by filtering on manifest data and printing out selected information.") @Arguments(arg = { "..." }) interface selectOptions
			extends Options {
		@Description("A simple assertion on a manifest header or an OSGi filter. Comparisons are case insensitive. The key 'resources' holds the pathnames of all resources and can also be asserted.") String where();

		@Description("A manifest header to print or: path, name, size, length, modified for information about the file, wildcards are allowed to print multiple headers. ") Collection<String> header();

		@Description("Print the key before the value") boolean key();

		@Description("Print the file name before the value") boolean name();

		@Description("Print the file path before the value") boolean path();
	}

	public void _select(selectOptions opts) throws Exception {
		PrintStream out = this.out;

		Filter filter = null;
		if (opts.where() != null) {
			String w = opts.where();
			if (!w.startsWith("("))
				w = "(" + w + ")";
			filter = new Filter(w);
		}

		Instructions instructions = new Instructions(opts.header());

		for (String s : opts._()) {
			Jar jar = getJar(s);
			if (jar == null) {
				err.println("no file " + s);
				continue;
			}

			Domain domain = Domain.domain(jar.getManifest());
			Hashtable<String, Object> ht = new Hashtable<String, Object>();
			Iterator<String> i = domain.iterator();
			Set<String> realNames = new HashSet<String>();

			while (i.hasNext()) {
				String key = i.next();
				String value = domain.get(key).trim();
				ht.put(key.trim().toLowerCase(), value);
				realNames.add(key);
			}
			ht.put("resources", jar.getResources().keySet());
			realNames.add("resources");
			if (filter != null) {
				if (!filter.match(ht))
					continue;
			}

			Set<Instruction> unused = new HashSet<Instruction>();
			Collection<String> select = instructions.select(realNames, unused, true);
			for (String h : select) {
				if (opts.path()) {
					out.print(jar.getSource().getAbsolutePath() + ":");
				}
				if (opts.name()) {
					out.print(jar.getSource().getName() + ":");
				}
				if (opts.key()) {
					out.print(h + ":");
				}
				out.println(ht.get(h.toLowerCase()));
			}
			for (Instruction ins : unused) {
				String literal = ins.getLiteral();
				if (literal.equals("name"))
					out.println(jar.getSource().getName());
				else if (literal.equals("path"))
					out.println(jar.getSource().getAbsolutePath());
				else if (literal.equals("size") || literal.equals("length"))
					out.println(jar.getSource().length());
				else if (literal.equals("modified"))
					out.println(new Date(jar.getSource().lastModified()));
			}
		}
	}

	/**
	 * Get the exports of a number of bnd files and combine them
	 */
	interface exportOptions extends Options {
		Collection<String> augments();
	}

	public void _exports(exportOptions opts) throws Exception {
		Parameters parameters = new Parameters();

		for (String fname : opts._()) {
			Jar jar = getJar(fname);
			try {
				Manifest m = jar.getManifest();
				Domain domain = Domain.domain(m);

				Parameters p = domain.getExportPackage();
				
				// Check for augments, we borrow properties from
				// the manifest and add them to the exports
				for (String augment : opts.augments()) {
					String v = domain.get(augment);
					if (v != null) {
						v = v.trim();
						for (String pname : p.keySet()) {
							System.err.println("Add " + augment + " to " + pname +" v=" + v);
							Attrs attrs = p.get(pname);
							attrs.put(augment, v);
						}
					}
				}
				
				parameters.putAll(p);
			} finally {
				jar.close();
			}
		}
		out.printf("Export-Package:");
		String del = " \\\n  ";
		for (String key : parameters.keySet()) {
			out.print(del);
			out.print(key);
			Attrs attrs = parameters.get(key);
			for (String name : attrs.keySet()) {
				if (name.equals("uses:"))
					continue;

				out.print(";");
				out.print(name);
				out.print("=");
				Processor.quote(out, attrs.get(name));
			}
			del = ", \\\n  ";
		}
		out.println();
	}

	/**
	 * Central routine to get a JAR with error checking
	 * 
	 * @param s
	 * @return
	 */
	Jar getJar(String s) {

		File f = getFile(s);
		if (!f.isFile()) {
			error("Not a file: %s", f);
			return null;
		}
		try {
			return new Jar(f);
		} catch (ZipException e) {
			error("Not a jar/zip file: %s", f);
		} catch (Exception e) {
			error("Opening file: %s", e, f);
		}
		return null;
	}
}
