package genepi.imputationserver.steps;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.collections4.ListUtils;

import cloudgene.sdk.internal.IExternalWorkspace;
import cloudgene.sdk.internal.WorkflowContext;
import cloudgene.sdk.internal.WorkflowStep;
import genepi.hadoop.HdfsUtil;
import genepi.hadoop.command.Command;
import genepi.imputationserver.steps.imputation.ImputationPipeline;
import genepi.imputationserver.steps.vcf.MergedVcfFile;
import genepi.imputationserver.util.DefaultPreferenceStore;
import genepi.imputationserver.util.FileChecksum;
import genepi.imputationserver.util.FileMerger;
import genepi.imputationserver.util.ImputationResults;
import genepi.imputationserver.util.ImputedChromosome;
import genepi.imputationserver.util.PasswordCreator;
import genepi.imputationserver.util.PgsPanel;
import genepi.io.FileUtil;
import genepi.io.text.LineWriter;
import genepi.riskscore.io.MetaFile;
import genepi.riskscore.io.OutputFile;
import genepi.riskscore.io.ReportFile;
import genepi.riskscore.io.SamplesFile;
import genepi.riskscore.tasks.CreateHtmlReportTask;
import genepi.riskscore.tasks.MergeReportTask;
import genepi.riskscore.tasks.MergeScoreTask;
import lukfor.progress.TaskService;
import lukfor.progress.tasks.Task;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CompressionEncryption extends WorkflowStep {

	private class BatchRunner implements Callable<String> {
		private WorkflowContext context;
		private LineWriter writer;
		private String tempdir;
		private String password;
		private List<String> chroms;
	    private long split_size;
	    private int nthreads2;

		public BatchRunner(WorkflowContext context, LineWriter writer, String tempdir, String password,
				   List<String> chroms,long split_size,int nthreads2) {
			this.context = context;
			this.writer = writer;
			this.password = password;
			this.tempdir = tempdir;
			this.chroms = chroms;
			this.split_size=split_size;
			this.nthreads2=nthreads2;
		}

		@Override
		public String call() throws Exception {
			List<String> res = new ArrayList<String>();
			for (String c : chroms) {
			    zipEncryptChr(context, c, writer, password, tempdir,split_size,nthreads2);
				res.add(c);
			}
			return "Chromosome(s) " + String.join(", ", res) + " finished";
		}
	}

	@Override
	public boolean run(WorkflowContext context) {
		int nthreads = 4; // default
		long ssz=0L;  // default
		int nthreads2=1; // default

		// currently not used
		//String outputScores = context.get("outputScores");
		//String pgsOutput = context.get("pgs_output");
		//PgsPanel pgsPanel = PgsPanel.loadFromProperties(context.getData("pgsPanel"));
		
		String output = context.get("outputimputation");
		String localOutput = context.get("local");
		String mode = context.get("mode");

		// context.log("output: "+output);
		// context.log("localOutput: "+localOutput);
		
		String password = context.get("password");
		if (password == null || (password != null && password.equals("auto"))) {
		    password = PasswordCreator.createPassword();
		}

		boolean phasingOnly = false;
		if (mode != null && mode.equals("phasing"))
			phasingOnly = true;

		// read config if mails should be sent
		File jobConfig = new File(FileUtil.path(getFolder(CompressionEncryption.class), "job.config"));
		DefaultPreferenceStore store = new DefaultPreferenceStore();
		if (jobConfig.exists())
		    store.load(jobConfig);
		else
		    context.log("Configuration file not available. Using default values.");
		//context.log("Configuration file '" + jobConfig.getAbsolutePath() + "' not available. Using default values.");

		// nthreads2
		if (store.getString("export.threads") != null && !store.getString("export.threads").equals("")) {
		    try {
			nthreads2 = Integer.parseInt(store.getString("export.threads"));
		    } catch (NumberFormatException e) {
			context.log(e.getMessage());
		    }
		}

		if (nthreads2 == 1)
		    context.log("Export: using 1 thread");
		else
		    context.log("Export: using " + nthreads2 + " threads");

		// split size
		if (store.getString("zip.split.size") != null && !store.getString("zip.split.size").equals("")) {
		    try {
			ssz=parseSplitSizeString(store.getString("zip.split.size"));
		    } catch (IllegalArgumentException e) {
			context.log(e.getMessage());
		    }
		}

		if (ssz == 0L)
		    context.log("Export: not splitting output ZIPs");
		else
		    context.log("Export: using split size "+ssz+" bytes");

		try {
			context.beginTask("Export data ...");
			// get sorted directories
			List<String> folders = HdfsUtil.getDirectories(output);
			ImputationResults imputationResults = new ImputationResults(folders, phasingOnly);
			Map<String, ImputedChromosome> imputedChromosomes = imputationResults.getChromosomes();

			// Andrei's code
			List<String> all_chr = new ArrayList<>(imputationResults.getChromosomes().keySet());
			int n = all_chr.size() / nthreads;
			if (all_chr.size() % nthreads != 0)
				n++;
			List<List<String>> chr_batches = ListUtils.partition(all_chr, n);
			context.log(chr_batches.size() + " batch(es):");
			for (List<String> x : chr_batches)
				context.log(String.join(", ", x));

			LineWriter writer = new LineWriter(FileUtil.path(localOutput, "results.md5"));

			String temp = FileUtil.path(localOutput, "temp");
			FileUtil.createDirectory(temp);

			ExecutorService pool = Executors.newFixedThreadPool(chr_batches.size());
			List<Callable<String>> callables = new ArrayList<Callable<String>>();
			for (int i = 0; i < chr_batches.size(); i++)
			    callables.add(new BatchRunner(context, writer, temp, password, chr_batches.get(i),ssz,nthreads2));
			List<Future<String>> res = pool.invokeAll(callables);
			for (Future<String> r : res)
				context.log(r.get());
			pool.shutdown();
			if (!pool.awaitTermination(60L, TimeUnit.SECONDS))
				context.log("Thread pool did not terminate");

			FileUtil.deleteDirectory(temp);

			writer.close();
			// delete temporary files
			HdfsUtil.delete(output);

			context.endTask("Exported data", WorkflowContext.OK); // TODO: If we decide to re-introduce PGS feature, we need to move this line accordingly
		} catch (Exception e) {
			e.printStackTrace();
			context.endTask("Data export failed: " + e.getMessage(), WorkflowContext.ERROR);
			return false;
		}

		// submit counters!
		context.submitCounter("samples");
		context.submitCounter("genotypes");
		context.submitCounter("chromosomes");
		context.submitCounter("runs");
		// submit panel and phasing method counters
		String reference = context.get("refpanel");
		String phasing = context.get("phasing");
		context.submitCounter("refpanel_" + reference);
		context.submitCounter("phasing_" + phasing);
		context.submitCounter("23andme-input");

		String notification = "no";
		if (store.getString("minimac.sendmail") != null && !store.getString("minimac.sendmail").equals(""))
			notification = store.getString("minimac.sendmail");
		String serverUrl = "https://imputationserver.helmholtz-munich.de";
		if (store.getString("server.url") != null && !store.getString("server.url").isEmpty())
			serverUrl = store.getString("server.url");

		// this part is not used anywhere
		//
		// String sanityCheck = "yes";
		// if (store.getString("sanitycheck") != null && !store.getString("sanitycheck").equals("")) {
		// 	sanityCheck = store.getString("sanitycheck");
		// }
		
		// send email
		if (notification.equals("yes")) {
			Object mail = context.getData("cloudgene.user.mail");
			Object name = context.getData("cloudgene.user.name");

			if (mail != null) {
				String subject = "Job " + context.getJobId() + " is complete.";
				String message = "Dear " + name + ",\nthe password for the imputation results is: " + password
						+ "\n\nThe results can be downloaded from " + serverUrl + "/start.html#!jobs/"
						+ context.getJobId() + "/results";
				try {
					context.sendMail(subject, message);
					context.ok("We have sent an email to <b>" + mail + "</b> with the password.");
					return true;
				} catch (Exception e) {
					context.error("Data compression failed: " + e.getMessage());
					return false;
				}
			} else {
				context.error("No email address found. Please enter your email address (Account -> Profile).");
				return false;
			}
		} else {
			context.ok("Email notification is disabled. All results are encrypted with password <b>" + password + "</b>");
			return true;
		}
	}

	// NOTE: This only exists in genepi. Related to PGS feature
	private CreateHtmlReportTask createReport(String outputFileHtml, String outputFileScores, String samples,
			ReportFile report, String template, boolean showDistribution) throws IOException, Exception {
		CreateHtmlReportTask task = new CreateHtmlReportTask();
		task.setApplicationName("");
		task.setVersion("PGS Server Beta <small>(" + ImputationPipeline.PIPELINE_VERSION + ")</small>");
		task.setShowCommand(false);
		task.setReport(report);
		task.setOutput(outputFileHtml);
		task.setData(new OutputFile(outputFileScores));
		task.setTemplate(template);
		task.setShowDistribution(showDistribution);
		if (new File(samples).exists()) {
			SamplesFile samplesFile = new SamplesFile(samples);
			samplesFile.buildIndex();
			task.setSamples(samplesFile);
		}
		return task;
	}

	// NOTE: genepi throws IOException while Andrei throws ZipException
    public void createEncryptedZipFile(File file, List<File> files, String password, boolean aesEncryption,long split_size)
			throws IOException {
		ZipParameters param = new ZipParameters();
		param.setEncryptFiles(true);
		param.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
		param.setCompressionMethod(CompressionMethod.STORE);
		param.setCompressionLevel(CompressionLevel.NORMAL);

		if (aesEncryption) {
			param.setEncryptionMethod(EncryptionMethod.AES);
			param.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
		}

		ZipFile zipFile = new ZipFile(file, password.toCharArray());
		if (split_size==0L){
		    zipFile.addFiles(files,param);
		}
		else{
		    zipFile.createSplitZipFile(files,param,true,split_size);
		}
		
		zipFile.close();
	}

    // using external call of 7z to create archive
    public void createEncryptedZipFile7z(String output_fname, List<String> fnames, String password,int threads) throws IOException,InterruptedException {
	List<String> cmd_args=new ArrayList<String>();
	cmd_args.add("/usr/bin/bash");
	cmd_args.add("-c");
	String s="/mnt/storage/bin/7z_wrapper.sh -o "+output_fname;
	for (String f:fnames){
	    s=s+" -i "+f;
	}
	cmd_args.add(s);

	ProcessBuilder builder = new ProcessBuilder(cmd_args);
	Map<String, String> env=builder.environment();
        env.put("PWD7Z",password);

	// cmd_args.add("/mnt/storage/bin/7z_wrapper.sh");
	// cmd_args.add("-p");
	// cmd_args.add(password);
	// cmd_args.add("-t");
	// cmd_args.add(String.valueOf(threads));	
	// cmd_args.add("-o");
	// cmd_args.add(output_fname);
	// for (String f:fnames){
	//     cmd_args.add("-i");
	//     cmd_args.add(f);
	// }
	
	// ProcessBuilder builder = new ProcessBuilder(cmd_args);
	File sink=new File("/dev/null");
	builder.redirectOutput(sink);
	builder.redirectError(sink);
	Process zip7z = builder.start();

	int exitCode = zip7z.waitFor();
	if (exitCode != 0) {
	    throw new IOException("Received exit code " + exitCode + " from command " + builder.command());
	}	
    }

    public void createEncryptedZipFile(File file, File source, String password, boolean aesEncryption,long split_size)
			throws IOException {
		List<File> files = new Vector<File>();
		files.add(source);
		createEncryptedZipFile(file, files, password, aesEncryption,split_size);
	}

    private long parseSplitSizeString(String str) throws IllegalArgumentException{
	Pattern pattern = Pattern.compile("^([1-9][0-9]*)([KMG])$");
	Matcher matcher = pattern.matcher(str);
	if (matcher.matches()){
	    long x=Long.parseLong(matcher.group(1),10);
	    String s=matcher.group(2);
	    if (s.equals("K")){
		return 1024L*x;
	    }
	    if (s.equals("M")){
		return 1024L*1024L*x;
	    }
	    if (s.equals("G")){
		return 1024L*1024L*1024L*x;
	    }
	}
	else{
	    throw new IllegalArgumentException("wrong split size string format");
	}
	return 0L;
    }

    
	// NOTE: this method only exists in Andrei's version
	private void zipEncryptChr(WorkflowContext context, String cname, LineWriter writer, String password,
				   String tempdir,long split_size,int nthreads2) throws Exception {
		String output = context.get("outputimputation");
		String localOutput = context.get("local");
		String localLogDir = context.get("logfile");
		String aesEncryptionValue = context.get("aesEncryption");
		String meta = context.get("meta");
		String mode = context.get("mode");

		boolean phasingOnly = false;
		if (mode != null && mode.equals("phasing")) {
			phasingOnly = true;
		}

		boolean mergeMetaFiles = !phasingOnly && (meta != null && meta.equals("yes"));
		boolean aesEncryption = (aesEncryptionValue != null && aesEncryptionValue.equals("yes"));

		ImputationResults imputationResults = new ImputationResults(HdfsUtil.getDirectories(output), phasingOnly);
		ImputedChromosome imputedChromosome = imputationResults.getChromosomes().get(cname);
		synchronized (this) {
			context.log("Starting exporting and merging chromosome " + cname);
		}

		// output files
		//ArrayList<File> files = new ArrayList<File>();
		ArrayList<String> filenames = new ArrayList<String>();

		// merge info files
		if (!phasingOnly) {
		    context.log("Merging and Gzipping INFO for " + cname);
		    String infoOutput = FileUtil.path(tempdir, "chr" + cname + ".info.gz");
		    FileMerger.mergeAndGzInfo(imputedChromosome.getInfoFiles(), infoOutput);
		    //files.add(new File(infoOutput));
		    filenames.add(infoOutput);
		    synchronized (this) {
			String checksum = FileChecksum.HashFile(new File(infoOutput), FileChecksum.Algorithm.MD5);
			//context.log("Checksum for "+infoOutput+": "+checksum);
		    }
		}

		// merge all dosage files
		String dosageOutput;
		if (phasingOnly) {
			dosageOutput = FileUtil.path(tempdir, "chr" + cname + ".phased.vcf.gz");
		} else {
			dosageOutput = FileUtil.path(tempdir, "chr" + cname + ".dose.vcf.gz");
		}
		MergedVcfFile vcfFile = new MergedVcfFile(dosageOutput);
		vcfFile.addHeader(context, imputedChromosome.getHeaderFiles());
		synchronized (this) {
		    context.log("Added header for " + cname);
		}
		for (String file : imputedChromosome.getDataFiles()) {
		    //synchronized (this) {
			    //context.log("Adding file " + file + " for " + cname);
		    //}
			vcfFile.addFile(HdfsUtil.open(file));
			HdfsUtil.delete(file);
		}
		vcfFile.close();
		synchronized (this) {
		    String checksum = FileChecksum.HashFile(new File(dosageOutput), FileChecksum.Algorithm.MD5);
		    //context.log("Checksum for "+dosageOutput+": "+checksum);
		}
		//files.add(new File(dosageOutput));
		filenames.add(dosageOutput);
		//synchronized (this) {
		    //context.log("Saving DOSE / PHASED for " + cname + " in " + dosageOutput);
		//}
		
		// merge all meta files
		if (mergeMetaFiles) {
			synchronized (this) {
				context.log("Merging meta files for "+cname);
			}
			String dosageMetaOutput = FileUtil.path(tempdir, "chr" + cname + ".empiricalDose.vcf.gz");
			MergedVcfFile vcfFileMeta = new MergedVcfFile(dosageMetaOutput);
			String headerMetaFile = imputedChromosome.getHeaderMetaFiles().get(0);
			//synchronized (this) {
			    //context.log("Using header from file " + headerMetaFile+" for "+cname);
			//}
			vcfFileMeta.addFile(HdfsUtil.open(headerMetaFile));

			for (String file : imputedChromosome.getDataMetaFiles()) {
			    //synchronized (this) {
				    //context.log("Adding META file " + file+" for "+cname);
				    //}
				vcfFileMeta.addFile(HdfsUtil.open(file));
				HdfsUtil.delete(file);
			}
			vcfFileMeta.close();
			synchronized (this) {
				context.log("Meta files merged for "+cname);
			}
			//files.add(new File(dosageMetaOutput));
			filenames.add(dosageMetaOutput);
			synchronized (this) {
			    String checksum = FileChecksum.HashFile(new File(dosageMetaOutput), FileChecksum.Algorithm.MD5);
			    //context.log("Checksum for "+dosageMetaOutput+": "+checksum);
			}
		}

		// create zip file
		synchronized (this) {
		    context.log("Creating ZIP for "+cname);
		}
		
		String fileName = "chr_" + cname + ".zip";
		String filePath = FileUtil.path(localOutput, fileName);
		//File file = new File(filePath);
		createEncryptedZipFile7z(filePath,filenames,password,nthreads2);
		//createEncryptedZipFile(file, files, password, aesEncryption,split_size);

		File D=new File(localOutput);
		//FileFilter fileFilter = new WildcardFileFilter("chr_"+cname+".z*");
		FileFilter fileFilter = new WildcardFileFilter("chr_"+cname+".zip");
		File flist [] = D.listFiles(fileFilter); // all parts of ZIP split
		
		for (File F:flist){
		    // add checksum to hash file
		    synchronized (this) {
			context.log("Creating file checksum for " + F.getName());
		    }
		    long checksumStart = System.currentTimeMillis();
		    //String checksum = FileChecksum.HashFile(new File(filePath), FileChecksum.Algorithm.MD5);
		    String checksum = FileChecksum.HashFile(F, FileChecksum.Algorithm.MD5);
		    synchronized (this) {
			writer.write(checksum + " " + F.getName());
		    }
		    long checksumEnd = (System.currentTimeMillis() - checksumStart) / 1000;
		    synchronized (this) {
			//context.log("File checksum for " + filePath + " created in " + checksumEnd + " seconds.");
			context.log("File checksum for " + F.getName() + " created in " + checksumEnd + " seconds.");
		    }
		}

		IExternalWorkspace externalWorkspace = context.getExternalWorkspace();
		if (externalWorkspace != null) {
		    File file = new File(filePath); 
			long start = System.currentTimeMillis();
			synchronized (this) {
				context.log("External Workspace '" + externalWorkspace.getName() + "' found");
			}
			synchronized (this) {
				context.log("Start file upload: " + filePath);
			}
			String url = externalWorkspace.upload("local", file);
			long end = (System.currentTimeMillis() - start) / 1000;
			synchronized (this) {
				context.log("Upload finished in  " + end + " sec. File Location: " + url);
			}
			synchronized (this) {
				context.log("Add " + localOutput + " to custom download");
			}
			String size = FileUtils.byteCountToDisplaySize(file.length());
			context.addDownload("local", fileName, size, url);
			FileUtil.deleteFile(filePath);
			synchronized (this) {
				context.log("File deleted: " + filePath);
			}
		} else {
			synchronized (this) {
				context.log("No external Workspace set.");
			}
		}
	}

    // not used
	public void createEncryptedZipFileFromFolder(File file, File folder, String password, boolean aesEncryption)
			throws IOException {
		ZipParameters param = new ZipParameters();
		param.setEncryptFiles(true);
		param.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);

		if (aesEncryption) {
			param.setEncryptionMethod(EncryptionMethod.AES);
			param.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
			param.setCompressionMethod(CompressionMethod.DEFLATE);
			param.setCompressionLevel(CompressionLevel.NORMAL);
		}

		ZipFile zipFile = new ZipFile(file, password.toCharArray());
		zipFile.addFolder(folder);
		zipFile.close();
	}

    // not used
	public void createZipFile(File file, File folder) throws IOException {
		ZipFile zipFile = new ZipFile(file);
		if (folder.isFile()) {
			zipFile.addFile(folder);
		} else {
			zipFile.addFolder(folder);
		}
		zipFile.close();
	}
}
