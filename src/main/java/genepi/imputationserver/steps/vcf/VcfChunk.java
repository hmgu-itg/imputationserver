package genepi.imputationserver.steps.vcf;

import java.text.DecimalFormat;

public class VcfChunk {
    private String chromosome;
    private String vcfFilename;
    private boolean phased = true;
    private int start;
    private int end;
    private DecimalFormat nf = new DecimalFormat("#0000000000");
    private int snps = 0;
    private int inReference = 0;
    private int samples;
	
    public VcfChunk() {}

    public VcfChunk(String line) {
	String[] tiles = line.split("\t");

	chromosome = tiles[0];
	start = Integer.parseInt(tiles[1]);
	end = Integer.parseInt(tiles[2]);
	phased = tiles[3].equals("VCF-PHASED");
	vcfFilename = tiles[4];

	if (tiles.length > 7) {
	    snps = Integer.parseInt(tiles[5]);
	    inReference = Integer.parseInt(tiles[6]);
	    samples = Integer.parseInt(tiles[7]);
	}
    }

    public String getChromosome() {
	return chromosome;
    }

    public void setChromosome(String chromosome) {
	this.chromosome = chromosome;
    }

    public String getVcfFilename() {
	return vcfFilename;
    }

    public void setVcfFilename(String vcfFilename) {
	this.vcfFilename = vcfFilename;
    }

    public boolean isPhased() {
	return phased;
    }

    public void setPhased(boolean phased) {
	this.phased = phased;
    }

    public int getStart() {
	return start;
    }

    public void setStart(int start) {
	this.start = start;
    }

    public int getEnd() {
	return end;
    }

    public void setEnd(int end) {
	this.end = end;
    }

    public String toString() {
	return getId();
    }

    public int getSnps() {
	return snps;
    }

    public void setSnps(int snps) {
	this.snps = snps;
    }

    public int getSamples() {
	return samples;
    }

    public void setSamples(int s) {
	this.samples = s;
    }

    public int getInReference() {
	return inReference;
    }

    public void setInReference(int inReference) {
	this.inReference = inReference;
    }

    public String serialize() {
	return chromosome + "\t" + start + "\t" + end + "\t"
	    + (phased ? "VCF-PHASED" : "VCF-UNPHASED") + "\t" + vcfFilename
	    + "\t" + snps + "\t" + inReference+"\t"+samples;
    }

    public String getId() {
	return "chunk_" + chromosome + "_" + nf.format(start) + "_"
	    + nf.format(end);

    }
	
    // chunk specific
    public int overallSnpsChunk = 0;
    public int validSnpsChunk = 0;
    public int foundInLegendChunk = 0;
    public int notFoundInLegendChunk = 0;
    public int[] snpsPerSampleCount = null;
    public BGzipLineWriter vcfChunkWriter;
    public 	int lastPos = 0;
    public boolean empty=true;
}
