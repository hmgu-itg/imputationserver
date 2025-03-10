package genepi.imputationserver.steps.imputation;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.ListUtils;
import org.apache.commons.io.FileUtils;
import org.codehaus.groovy.control.CompilationFailedException;

import genepi.hadoop.log.Log;
import genepi.hadoop.command.Command;
import genepi.imputationserver.steps.vcf.VcfChunk;
import genepi.imputationserver.steps.vcf.VcfChunkOutput;
import genepi.imputationserver.util.DefaultPreferenceStore;
import genepi.io.FileUtil;
import genepi.riskscore.io.Chunk;
import genepi.riskscore.io.PGSCatalog;
import genepi.riskscore.io.formats.RiskScoreFormatFactory.RiskScoreFormat;
import genepi.riskscore.tasks.ApplyScoreTask;
import groovy.text.SimpleTemplateEngine;
import htsjdk.samtools.util.StopWatch;
import lukfor.progress.TaskService;
import lukfor.progress.tasks.Task;

public class ImputationPipeline {
    public static final String PIPELINE_VERSION = "helmholtz-munich-imputationserver-2.1.3";
    public static final String IMPUTATION_VERSION = "minimac-v4.1.6";
    public static final String BEAGLE_VERSION = "beagle.18May20.d20.jar";
    public static final String EAGLE_VERSION = "eagle-2.4.1";
    private String minimacCommand;
    private String minimacParams;
    private String eagleCommand;
    private String eagleParams;
    private String beagleCommand;
    private String beagleParams;
    private String tabixCommand;
    private int minimacWindow;
    private int minimacDecay;
    private int phasingWindow;
    private double minR2;
    private String refFilename;
    private String mapMinimac;
    
    private int eagleThreads;
    private int minimac4Threads;
    private int minimac4_temp_buffer;
    private String minimac4_temp_prefix;
    
    private String mapEagleFilename = "";
    private String refEagleFilename = "";
    private String refBeagleFilename = "";
    private String mapBeagleFilename = "";
    private String includeScoreFilename = null;
    private String build = "hg19";
    private boolean phasingOnly;
    private String phasingEngine = "";
    private String scores;

    private ImputationStatistic statistic = new ImputationStatistic();
    private SimpleTemplateEngine engine = new SimpleTemplateEngine();

    public boolean execute(VcfChunk chunk, VcfChunkOutput output,Log log) throws InterruptedException, IOException {
	log.info("Starting pipeline for chunk " + chunk + " [Phased: " + chunk.isPhased() + "]");
	System.out.println("Starting pipeline for chunk " + chunk + " [Phased: " + chunk.isPhased() + "]...");

		if (!new File(refFilename).exists()) {
			log.info("ReferencePanel '" + refFilename + "' not found.");
			System.out.println("ReferencePanel '" + refFilename + "' not found.");
			return false;
		}

		if (!new File(output.getVcfFilename()).exists()) {
			log.info("vcf.gz file not found: " + output.getVcfFilename());
			System.out.println("vcf.gz file not found: " + output.getVcfFilename());
			return false;
		}

		// replace X.nonpar / X.par with X needed by eagle and minimac
		if (chunk.getChromosome().startsWith("X.")) {
			output.setChromosome("X");
		}

		// create tabix index
		Command tabix = new Command(tabixCommand);
		tabix.setSilent(false);
		tabix.setParams(output.getVcfFilename());
			log.info("TABIX Command: " + tabix.getExecutedCommand());
		System.out.println("Command: " + tabix.getExecutedCommand());
		if (tabix.execute() != 0) {
			log.info("Error during index creation: " + tabix.getStdOut());
			System.out.println("Error during index creation: " + tabix.getStdOut());
			return false;
		}

		if (chunk.isPhased()) {

			FileUtils.moveFile(new File(output.getVcfFilename()), new File(output.getPhasedVcfFilename()));
			log.info("Chunk already phased. Move file " + output.getVcfFilename() + " to "
					+ output.getPhasedVcfFilename() + ".");
			System.out.println("Chunk already phased. Move file " + output.getVcfFilename() + " to "
					+ output.getPhasedVcfFilename() + ".");

		} else {

			// phasing
			long time = System.currentTimeMillis();

			boolean successful = false;
			String phasingVersion;
			if (phasingEngine.equals("beagle")) {

				if (!new File(refBeagleFilename).exists()) {
			log.info("Beagle: Reference '" + refBeagleFilename + "' not found.");
					System.out.println("Beagle: Reference '" + refBeagleFilename + "' not found.");
					return false;
				}
				successful = phaseWithBeagle(chunk, output, refBeagleFilename, mapBeagleFilename);
				phasingVersion = BEAGLE_VERSION;
			} else {

				if (!new File(refEagleFilename).exists()) {
			log.info("Eagle: Reference '" + refEagleFilename + "' not found.");
					System.out.println("Eagle: Reference '" + refEagleFilename + "' not found.");
					return false;
				}
				log.info("STARTING PHASING WITH EAGLE");
				successful = phaseWithEagle(chunk, output, refEagleFilename, mapEagleFilename,log);
				phasingVersion = EAGLE_VERSION;
			}

			time = (System.currentTimeMillis() - time) / 1000;

			statistic.setPhasingTime(time);

			if (successful) {
			log.info("  " + phasingVersion + " finished successfully. [" + time + " sec]");
				System.out.println("  " + phasingVersion + " finished successfully. [" + time + " sec]");
			} else {
			log.info("  " + phasingVersion + " failed. [" + time + " sec]");
				System.out.println("  " + phasingVersion + " failed. [" + time + " sec]");
				return false;
			}

		}

		if (phasingOnly)

		{
			System.out.println("Phasing-only mode, no imputation started.");
			return true;
		}

		// Imputation

		long time = System.currentTimeMillis();
			log.info("STARTING IMPUTATION");
			boolean successful = imputeVCF(output,log);
			log.info("IMPUTATION DONE");
		time = (System.currentTimeMillis() - time) / 1000;

		statistic.setImputationTime(time);

		if (successful) {
		    log.info("  " + IMPUTATION_VERSION + " finished successfully. [" + time + " sec]");
			System.out.println("  " + IMPUTATION_VERSION + " finished successfully. [" + time + " sec]");
		} else {
			String stdOut = FileUtil.readFileAsString(output.getPrefix() + ".minimac.out");
			String stdErr = FileUtil.readFileAsString(output.getPrefix() + ".minimac.err");
			log.info("  " + IMPUTATION_VERSION + " failed. [" + time + " sec]\n\nStdOut:\n" + stdOut+ "\nStdErr:\n" + stdErr);
			System.out.println("  " + IMPUTATION_VERSION + " failed. [" + time + " sec]\n\nStdOut:\n" + stdOut
					+ "\nStdErr:\n" + stdErr);
			return false;
		}

		if (scores != null) {

			System.out.println("  Starting PGS calculation '" + scores + "'...");

			StopWatch watch = new StopWatch();
			watch.start();
			successful = runPgsCalc(output);
			watch.stop();

			if (successful) {
				statistic.setPgsTime(watch.getElapsedTimeSecs());
				System.out.println("  " + "PGS Calc finished successfully. [" + watch.getElapsedTimeSecs() + " sec]");
				return true;
			} else {
				System.out.println("  " + "PGS Calc failed. [" + watch.getElapsedTimeSecs() + " sec]");
				return false;
			}

		} else {
			System.out.println("  PGS Calculaton not executed. ");
			return true;
		}

	}

    public boolean phaseWithEagle(VcfChunk input, VcfChunkOutput output, String reference, String mapFilename,Log log)
			throws IOException {
	
				log.info("PHASING WITH EAGLE");

		// calculate phasing positions
		int start = input.getStart() - phasingWindow;
		if (start < 1) {
			start = 1;
		}
		int end = input.getEnd() + phasingWindow;

		String phasedPrefix = ".eagle.phased";

		// set parameters
		Map<String, Object> binding = new HashMap<String, Object>();
		binding.put("ref", reference);
		binding.put("vcf", output.getVcfFilename());
		binding.put("map", mapFilename);
		binding.put("prefix", output.getPrefix() + phasedPrefix);
		binding.put("start", start);
		binding.put("end", end);
		log.info("Eagle chromosome: " + input.getChromosome());
		// add --chrom to binding
		binding.put("chrom",input.getChromosome());
		// add eagle_threads to binding -----------------------
		binding.put("eagle_threads",eagleThreads);
		// ----------------------------------------------------

		String[] params = createParams(eagleParams, binding);

		// eagle command
		Command eagle = new Command(eagleCommand);
		//eagle.setLog(log);
		eagle.setSilent(false);
		eagle.setParams(params);
		eagle.saveStdOut(output.getPrefix() + ".eagle.out");
		eagle.saveStdErr(output.getPrefix() + ".eagle.err");
		log.info("Eagle Command: " + eagle.getExecutedCommand());

		int status = eagle.execute();

		if (status != 0) {
		    log.info("Eagle return status: " + status);
			System.out.println("Eagle return status: " + status);
			return false;
		}

		// rename
		new File(output.getPrefix() + phasedPrefix + ".vcf.gz").renameTo(new File(output.getPhasedVcfFilename()));

		// haps to vcf
		return true;
	}

	public boolean phaseWithBeagle(VcfChunk input, VcfChunkOutput output, String reference, String mapFilename)
			throws IOException {

		// calculate phasing positions
		int start = input.getStart() - phasingWindow;
		if (start < 1) {
			start = 1;
		}
		int end = input.getEnd() + phasingWindow;

		String phasedPrefix = ".beagle.phased";

		// set parameters
		Map<String, Object> binding = new HashMap<String, Object>();
		binding.put("beagle", beagleCommand);
		binding.put("ref", reference);
		binding.put("vcf", output.getVcfFilename());
		binding.put("prefix", output.getPrefix() + phasedPrefix);
		binding.put("chr", input.getChromosome());
		binding.put("start", start);
		binding.put("end", end);
		binding.put("map", mapFilename);

		String[] params = createParams(beagleParams, binding);

		// beagle command
		Command beagle = new Command("/usr/bin/java");
		beagle.setSilent(false);
		beagle.setParams(params);
		// beagle.saveStdOut(output.getPrefix() + ".beagle.out");
		// beagle.saveStdErr(output.getPrefix() + ".beagle.err");
		System.out.println("Command: " + beagle.getExecutedCommand());
		if (beagle.execute() != 0) {
			return false;
		}

		// rename
		new File(output.getPrefix() + phasedPrefix + ".vcf.gz").renameTo(new File(output.getPhasedVcfFilename()));

		// haps to vcf
		return true;
	}

	public boolean imputeVCF(VcfChunkOutput output,Log log)
			throws InterruptedException, IOException, CompilationFailedException {

		// create tabix index
		Command tabix = new Command(tabixCommand);
		tabix.setSilent(false);
		tabix.setParams(output.getPhasedVcfFilename());
		System.out.println("Command: " + tabix.getExecutedCommand());
		if (tabix.execute() != 0) {
			System.out.println("Error during index creation: " + tabix.getStdOut());
			return false;
		}

		String chr = "";
		if (build.equals("hg38")) {
			chr = "chr" + output.getChromosome();
		} else {
			chr = output.getChromosome();
		}

		// set parameters
		Map<String, Object> binding = new HashMap<String, Object>();
		binding.put("ref", refFilename);
		binding.put("vcf", output.getPhasedVcfFilename());
		binding.put("start", output.getStart());
		binding.put("end", output.getEnd());
		binding.put("window", minimacWindow);
		binding.put("prefix", output.getPrefix());
		binding.put("chr", chr);
		binding.put("unphased", false);
		binding.put("mapMinimac", mapMinimac);
		binding.put("minR2", minR2);
		binding.put("decay", minimacDecay);
		// add minimac_threads to binding ---------------------
		binding.put("minimac_threads",minimac4Threads);
		// ----------------------------------------------------
		// add minimac temp buffer/prefix to binding ----------
		binding.put("minimac_temp_prefix",minimac4_temp_prefix);
		binding.put("minimac_temp_buffer",minimac4_temp_buffer);
		// ----------------------------------------------------

		String[] params = createParams(minimacParams, binding);

		// minimac command
		Command minimac = new Command(minimacCommand);
		minimac.setSilent(false);
		minimac.setParams(params);
		minimac.saveStdOut(output.getPrefix() + ".minimac.out");
		minimac.saveStdErr(output.getPrefix() + ".minimac.err");

		for (String subs: minimac.getExecutedCommand().split("\\s+")){
		    log.info("Minimac Command: " + subs);
		}
		int status = minimac.execute();
		System.out.println("Minimac return status: " + status);

		return (status == 0);

	}

	// Risk score calculation
	private boolean runPgsCalc(VcfChunkOutput output) {

		String cacheDir = new File(output.getScoreFilename()).getParent();
		PGSCatalog.CACHE_DIR = cacheDir;

		if (scores == null) {
			System.out.println("PGS calcuation failed. No score files set. ");
			return false;
		}

		try {

			Chunk scoreChunk = new Chunk();
			scoreChunk.setStart(output.getStart());
			scoreChunk.setEnd(output.getEnd());

			ApplyScoreTask task = new ApplyScoreTask();
			task.setVcfFilename(output.getImputedVcfFilename());
			task.setChunk(scoreChunk);
			task.setRiskScoreFilenames(new String[] { scores });
			if (includeScoreFilename != null && !includeScoreFilename.isEmpty()){
				task.setIncludeScoreFilename(includeScoreFilename);
			}

			// TODO: enable fix-strand-flips
			// task.setFixStrandFlips(true);
			// task.setRemoveAmbiguous(true);

			task.setOutputReportFilename(output.getScoreFilename() + ".json");
			task.setOutput(output.getScoreFilename());

			TaskService.setAnsiSupport(false);
			List<Task> runningTasks = TaskService.run(task);
			for (Task runningTask : runningTasks) {
				if (!runningTask.getStatus().isSuccess()) {
					System.out.println("PGS-Calc failed: " + runningTask.getStatus().getThrowable());
					runningTask.getStatus().getThrowable().printStackTrace();
					return false;
				}
			}

			return true;

		} catch (Exception e) {
			e.printStackTrace();
			return false;
		} catch (Error e) {
			e.printStackTrace();
			return false;
		}

	}

	public void setTabixCommand(String tabixCommand) {
		this.tabixCommand = tabixCommand;
	}

	public void setRefFilename(String refFilename) {
		this.refFilename = refFilename;
	}

	public void setMapMinimac(String mapMinimac) {
		this.mapMinimac = mapMinimac;
	}

	public void setMapEagleFilename(String mapEagleFilename) {
		this.mapEagleFilename = mapEagleFilename;
	}

	public void setRefEagleFilename(String refEagleFilename) {
		this.refEagleFilename = refEagleFilename;
	}

	public void setRefBeagleFilename(String refBeagleFilename) {
		this.refBeagleFilename = refBeagleFilename;
	}

	public void setIncludeScoreFilename(String includeScoreFilename) {
		this.includeScoreFilename = includeScoreFilename;
	}

	public void setMinimacCommand(String minimacCommand, String minimacParams) {
		this.minimacCommand = minimacCommand;
		this.minimacParams = minimacParams;
	}

	public void setMinimacWindow(int minimacWindow) {
		this.minimacWindow = minimacWindow;
	}

	public void setEagleCommand(String eagleCommand, String eagleParams) {
		this.eagleCommand = eagleCommand;
		this.eagleParams = eagleParams;
	}

	public void setBeagleCommand(String beagleCommand, String beagleParams) {
		this.beagleCommand = beagleCommand;
		this.beagleParams = beagleParams;
	}

	public void setPhasingWindow(int phasingWindow) {
		this.phasingWindow = phasingWindow;
	}

	public void setEagleThreads(int n) {
		this.eagleThreads=n;
	}

	public void setMinimac4Threads(int n) {
		this.minimac4Threads=n;
	}
    
	public void setMinimac4TempBuffer(int n) {
		this.minimac4_temp_buffer=n;
	}

	public void setMinimac4TempPrefix(String p) {
		this.minimac4_temp_prefix=p;
	}

	public void setBuild(String build) {
		this.build = build;
	}

	public boolean isPhasingOnly() {
		return phasingOnly;
	}

	public void setPhasingOnly(boolean phasingOnly) {
		this.phasingOnly = phasingOnly;
	}

	public void setScores(String scores) {
		this.scores = scores;
	}

	public void setPhasingEngine(String phasingEngine) {
		this.phasingEngine = phasingEngine;
	}

	public ImputationStatistic getStatistic() {
		return statistic;
	}

	protected String[] createParams(String template, Map<String, Object> bindings) throws IOException {

		try {
			String outputTemplate = "";
			outputTemplate = engine.createTemplate(template).make(bindings).toString();
			return outputTemplate.split("\\s+");
		} catch (Exception e) {
			throw new IOException(e);
		}

	}

	public String getMapBeagleFilename() {
		return mapBeagleFilename;
	}

	public void setMapBeagleFilename(String mapBeagleFilename) {
		this.mapBeagleFilename = mapBeagleFilename;
	}

	public void setMinR2(double minR2) {
		this.minR2 = minR2;
	}

	public void setDecay(int decay) {
		this.minimacDecay = decay;

	}

}
