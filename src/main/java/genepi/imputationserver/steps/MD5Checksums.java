package genepi.imputationserver.steps;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.util.Arrays;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import cloudgene.sdk.internal.WorkflowContext;
import cloudgene.sdk.internal.WorkflowStep;
import genepi.io.FileUtil;

public class MD5Checksums extends WorkflowStep {
    @Override
    public boolean run(WorkflowContext context) {
	MessageDigest digest=null;
	String files=context.get("files");
	String outfile=context.get("md5sums");
	    
	context.beginTask("Calculating md5 sums of the input VCFs");
	if (!new File(files).exists()) {
	    context.endTask("No input folder specified", WorkflowContext.ERROR);
	    return false;
	}
	    
	if (!new File(outfile).exists()) {
	    context.endTask("No MD5 output file specified", WorkflowContext.ERROR);
	    return false;
	}

	try{
	    digest=MessageDigest.getInstance("MD5");
	}catch (NoSuchAlgorithmException e){
	    context.endTask("Exception: "+e.toString(), WorkflowContext.ERROR);
	    return false;
	}

	String[] vcfFiles=FileUtil.getFiles(files, "*.vcf.gz$|*.vcf$");
	if (vcfFiles.length == 0) {
	    context.endTask("The provided files are not VCF files (see <a href=\"/start.html#!pages/help\">Help</a>).",WorkflowContext.ERROR);
	    return false;
	}

	Arrays.sort(vcfFiles);
	PrintWriter pw=null;
	try {
	    pw=new PrintWriter(new FileWriter(new File(outfile)));
	} catch (IOException e) {
	    context.endTask("Exception: "+e.toString(), WorkflowContext.ERROR);
	    return false;
	}
	for (String fname: vcfFiles){
	    try{
		String res=checksum(digest,new File(fname));
		pw.println(fname+" "+res);
		context.log("checksum: "+fname+" "+res);
	    }catch (IOException e){
		context.endTask("Exception: "+e.toString(), WorkflowContext.ERROR);
		if (pw != null) {
		    pw.close();
		}
		return false;
	    }
	}
	if (pw != null) {
	    pw.close();
	}
	    
	context.endTask("checksums done",WorkflowContext.OK);
	return true;	    
    }

    private String checksum(MessageDigest digest,File file) throws IOException{
        FileInputStream fis=new FileInputStream(file); 
        // Create byte array to read data in chunks
        byte[] byteArray = new byte[1024];
        int bytesCount = 0;
 
        while ((bytesCount = fis.read(byteArray)) != -1){
	    digest.update(byteArray, 0, bytesCount);
	}
	fis.close();
 
	byte[] bytes = digest.digest();
        StringBuilder sb=new StringBuilder();
       
        for (int i=0; i<bytes.length; i++) {
            sb.append(Integer.toString((bytes[i] & 0xff) + 0x100, 16).substring(1));
        }
	
        return sb.toString();
    }    
}
