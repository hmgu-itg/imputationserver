package genepi.imputationserver.steps.vcf;

import genepi.io.FileUtil;

public class VcfChunkOutput extends VcfChunk {

	private String prefix;
	private String imputedVcfFilename;
	private String metaVcfFilename;
	private String phasedVcfFilename;
	private String scoreFilename;
	private String eagleOutFilename;
	private String eagleErrFilename;
	private String minimacOutFilename;
	private String minimacErrFilename;

	private String infoFilename;

	public VcfChunkOutput(VcfChunk chunk, String outputFolder) {

		prefix = FileUtil.path(outputFolder, chunk.getId());
		imputedVcfFilename = prefix + ".dose.vcf.gz";
		metaVcfFilename = prefix + ".empiricalDose.vcf.gz";
		infoFilename = prefix + ".info";
		eagleOutFilename = prefix + ".eagle.out";
		eagleErrFilename = prefix + ".eagle.err";
		minimacOutFilename = prefix + ".minimac.out";
		minimacErrFilename = prefix + ".minimac.err";
		phasedVcfFilename = prefix + ".phased.vcf.gz";
		scoreFilename = prefix + ".scores.csv";

		setVcfFilename(prefix + ".vcf.gz");
		setChromosome(chunk.getChromosome());
		setStart(chunk.getStart());
		setEnd(chunk.getEnd());
		setPhased(chunk.isPhased());
	}

	public String getMetaVcfFilename() {
		return metaVcfFilename;
	}

	public void setMetaVcfFilename(String metaVcfFilename) {
		this.metaVcfFilename = metaVcfFilename;
	}

	public String getPrefix() {
		return prefix;
	}

	public String getInfoFilename() {
		return infoFilename;
	}

	public String getEagleOutFilename() {
		return eagleOutFilename;
	}

	public String getEagleErrFilename() {
		return eagleErrFilename;
	}

	public String getMinimacOutFilename() {
		return minimacOutFilename;
	}

	public String getMinimacErrFilename() {
		return minimacErrFilename;
	}

	public String getImputedVcfFilename() {
		return imputedVcfFilename;
	}

	public String getPhasedVcfFilename() {
		return phasedVcfFilename;
	}
	
	public String getScoreFilename() {
		return scoreFilename;
	}

}
