import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import com.adlibsoftware.authorize.AES;
import com.adlibsoftware.client.ArrayOfMetadataItem;
import com.adlibsoftware.client.MetadataItem;
import com.adlibsoftware.client.MetadataType;
import com.adlibsoftware.client.Payload;
import com.adlibsoftware.client.RenditionType;
import com.adlibsoftware.integration.Common;
import com.adlibsoftware.integration.DownloadFileNamingMode;
import com.adlibsoftware.integration.JobManagementServiceClient;
import com.adlibsoftware.integration.ProcessedJobResponse;
import com.adlibsoftware.integration.Settings;

public class JavaDocSample {

	/**
	 * Used for javadoc Overview
	 */
	public static void sample(String[] args) {
		try {
			Settings settings = new Settings();
			settings.setRepositoryName("CustomIntegration");
			// replace [computer] with Adlib Service Server fully qualified name
			settings.setJobManagementServiceWsdlUrl(new URL("https://[computer]:55583/Adlib/Services/JobManagement.svc?wsdl"));
			settings.setTokenServiceUrl(new URL("https://[computer]:8088/connect/token"));
	
			// should be AD/LDAP username that has access within Adlib (e.g. Management console credentials)
			settings.setTokenServiceUsername("adlibadmin");
			// secret key should be kept safe (keystore, etc.)
			settings.setSecretKey("s3cr3tk3y!");
			// example where plain text password is passed in (normally this would already be encrypted or retrieved from keystore)
			String encryptedPassword = AES.encrypt(args[0]);
			settings.setTokenServiceEncryptedPassword(encryptedPassword); 
	
			JobManagementServiceClient client = new JobManagementServiceClient(settings, true);
	
			// TODO: fill in Payload information with real file data
			Payload inputPayload = new Payload();
			// Add payload metadata
			ArrayOfMetadataItem payloadMetadata = new ArrayOfMetadataItem();
			MetadataItem metadata = new MetadataItem();
			metadata.setName("ClientJobId");
			metadata.setValue("1");
			metadata.setType(MetadataType.STRING);
			payloadMetadata.getMetadataItem().add(metadata);
			inputPayload.setMetadata(payloadMetadata);
			
			// Add file
			List<File> files = new ArrayList<File>();
			files.add(new File("C:\\temp\\Adlib\\sampleFile.txt"));			
			Common.setInputFiles((File[])files.toArray());			
	
			// Example 1: submit job (requires that files are on a share accessible by Adlib)
			long fileId = client.submitJob(settings.getRepositoryName(), inputPayload);
	
			// 1.1: wait for it to complete
			ProcessedJobResponse processedJob = client.waitForJobToProcess(fileId, settings.getDefaultTimeout(),
									settings.getDefaultPollingInterval());
	
			// TODO: Examine output Payload from processedJobs and copy files, etc.			
			Payload outputPayload = processedJob.getOutputPayload();
			System.out.println(String.format("File ID: %s; Job status: %s; Output file count: %s", 
					fileId, processedJob.getJobState(),  outputPayload.getFiles().getJobFile().size()));
			
			// Example 2: sample synchronous job
			processedJob = client.submitSynchronousJob(
							settings.getRepositoryName()
							, inputPayload
							, settings.getDefaultTimeout()
							, settings.getDefaultPollingInterval());
			
			// TODO: Examine output Payload from processedJobs and copy files, etc.
			outputPayload = processedJob.getOutputPayload();
			System.out.println(String.format("File ID: %s; Job status: %s; Output file count: %s", 
					fileId, processedJob.getJobState(),  outputPayload.getFiles().getJobFile().size()));
						
			
			// Example 3: stream job (JobFile list can be local file paths)
			fileId = client.streamJob(settings.getRepositoryName(), inputPayload);
			
			// 3.1 wait for completion
			processedJob = client.waitForJobToProcess(fileId, settings.getDefaultTimeout(),
					settings.getDefaultPollingInterval());
			
			if (processedJob.isSuccessful()) {
				// 3.2 download output
				File downloadDirectory = new File("c:\\temp\\Adlib");
				List<File> downloadedFiles = client.downloadLibraryRendition(processedJob, downloadDirectory, RenditionType.PDF, DownloadFileNamingMode.REPLACE_EXTENSION, null);
				System.out.println(String.format("File ID: %s; Job status: %s; Output file count: %s", 
						fileId, processedJob.getJobState(),  downloadedFiles.size()));
			}
			
		}
		catch (Exception e) {
			e.printStackTrace(System.err);
			System.err.println(Common.getFriendlyError(e));
			System.exit(1);
		}
	}
}
