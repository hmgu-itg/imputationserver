package genepi.imputationserver.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;

public class DefaultPreferenceStore {

	private Properties properties = new Properties(defaults());

	public DefaultPreferenceStore(Configuration configuration) {
		load(configuration);
	}

	public void load(File file) {
		try {
			properties.load(new FileInputStream(file));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public DefaultPreferenceStore() {

	}

	public void load(Configuration configuration) {
		Map<String, String> pairs = configuration.getValByRegex("cloudgene.*");
		for (String key : pairs.keySet()) {
			String cleanKey = key.replace("cloudgene.", "");
			String value = pairs.get(key);
			properties.setProperty(cleanKey, value);
		}
	}

	public void write(Configuration configuration) {

		for (Object key : properties.keySet()) {
			String newKey = "cloudgene." + key.toString();
			String value = properties.getProperty(key.toString());
			configuration.set(newKey, value);
		}

	}

	public String getString(String key) {
		return properties.getProperty(key);
	}

	public void setString(String key, String value) {
		properties.setProperty(key, value);
	}

	public Set<Object> getKeys() {
		return new HashSet<Object>(Collections.list(properties.propertyNames()));
	}

	public static Properties defaults() {

		Properties defaults = new Properties();
		defaults.setProperty("chunksize", "20000000");
		defaults.setProperty("phasing.window", "5000000");
		defaults.setProperty("minimac.window", "500000");
		defaults.setProperty("minimac.decay", "0");
		defaults.setProperty("minimac.sendmail", "no");
		defaults.setProperty("server.url", "https://imputationserver.helmholtz-munich.de");
		defaults.setProperty("minimac.tmp", "/mnt/storage/minimac.tmp");
		defaults.setProperty("big.job.size", "25000");
		defaults.setProperty("minimac4.temp.buffer", "1000");
		defaults.setProperty("minimac4.temp.prefix", "/mnt/storage/minimac.tmp");
		defaults.setProperty("minimac.command",
				"--region ${chr}:${start}-${end} --overlap ${window} --output ${prefix}.dose.vcf.gz --output-format vcf.gz --format GT,DS,GP,HDS --min-ratio 0.00001 --decay ${decay} --temp-prefix ${minimac_temp_prefix} --temp-buffer ${minimac_temp_buffer} --all-typed-sites --sites ${prefix}.info --threads ${minimac_threads} --empirical-output ${prefix}.empiricalDose.vcf.gz ${minR2 != 0 ? '--min-r2 ' + minR2 : ''}  ${mapMinimac != null ? '--map ' + mapMinimac : ''} ${ref} ${vcf}");
		defaults.setProperty("eagle.command",
				"--vcfRef ${ref} --vcfTarget ${vcf} --geneticMapFile ${map} --outPrefix ${prefix} --bpStart ${start} --bpEnd ${end} --vcfOutFormat z --keepMissingPloidyX --numThreads ${eagle_threads}");
		defaults.setProperty("beagle.command",
				"-jar ${beagle} ref=${ref} gt=${vcf} out=${prefix} nthreads=1 chrom=${chr}:${start}-${end} map=${map} impute=false");
		defaults.setProperty("ref.fasta", "v37");
		defaults.setProperty("contact.name", "Imputation Server Support");
		defaults.setProperty("contact.email", "support@imputationserver.helmholtz-munich.de");
		defaults.setProperty("hg38Tohg19", "chains/hg38ToHg19.over.chain.gz");
		defaults.setProperty("hg19Tohg38", "chains/hg19ToHg38.over.chain.gz");
		defaults.setProperty("sanitycheck", "yes");

		return defaults;
	}

}
