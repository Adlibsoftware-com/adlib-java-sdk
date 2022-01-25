import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import com.adlibsoftware.authorize.AES;
import com.adlibsoftware.client.ArrayOfMetadataItem;
import com.adlibsoftware.client.MetadataItem;
import com.adlibsoftware.client.MetadataType;
import com.adlibsoftware.client.OrchestrationFileStatusResponse;
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
	@SuppressWarnings("unused")
	private static void sample(String[] args) {
		try {
			Settings settings = new Settings();
			settings.setRepositoryName("CustomIntegration"); // must match repository name in Adlib
			// replace [computer] with Adlib Service Server fully qualified name
			settings.setJobManagementServiceWsdlUrl(new URL("https://[computer]:55583/Adlib/Services/JobManagement.svc?wsdl"));
			settings.setTokenServiceUrl(new URL("https://[computer]:8088/connect/token"));
	
			// should be AD/LDAP username that has access within Adlib (e.g. Management console credentials)
			settings.setTokenServiceUsername("adlibadmin");
			// secret key and/or password should be kept safe (keystore, etc.)
			settings.setSecretKey("s3cr3tk3y!");
			// example where plain text password is passed in (normally this would already be encrypted or retrieved from keystore)
			String encryptedPassword = AES.encrypt(args[0]);
			settings.setTokenServiceEncryptedPassword(encryptedPassword); 
	
			// Initialize client
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
			files.add(new File("c:\\temp\\sampleFile.txt"));			
			Common.setInputFiles((File[])files.toArray());			
	
			// Example 1: submit job and get ID back (requires that files are on a share accessible by Adlib)
			// -------------------------------------------------------------------------------
			long fileId = client.submitJob(settings.getRepositoryName(), inputPayload);
	
			// 1.1: wait for it to complete (or until timeout occurs)
			ProcessedJobResponse processedJob = client.waitForJobToProcess(fileId, settings.getDefaultTimeout(),
									settings.getDefaultPollingInterval());
	
			
			if (processedJob.isSuccessful()) {
				// TODO: Examine output Payload from processedJobs and copy files, etc.
				Payload outputPayload = processedJob.getOutputPayload();
				System.out.println(String.format("File ID: %s; Job status: %s; Output file count: %s", 
						fileId, processedJob.getJobState(),  outputPayload.getFiles().getJobFile().size()));
			} else {
				System.out.println(String.format("File ID: %s; Job status: %s; Error: %s", 
						fileId, processedJob.getJobState(), processedJob.getExceptionInfo()));
			}
			
			
			// Example 2: sample synchronous job (requires that files are on a share accessible by Adlib)
			// -------------------------------------------------------------------------------
			processedJob = client.submitSynchronousJob(
							settings.getRepositoryName()
							, inputPayload
							, settings.getDefaultTimeout()
							, settings.getDefaultPollingInterval());
			
			if (processedJob.isSuccessful()) {
				// TODO: Examine output Payload from processedJobs and copy files, etc.
				Payload outputPayload = processedJob.getOutputPayload();
				System.out.println(String.format("File ID: %s; Job status: %s; Output file count: %s", 
						fileId, processedJob.getJobState(),  outputPayload.getFiles().getJobFile().size()));
			} else {
				System.out.println(String.format("File ID: %s; Job status: %s; Error: %s", 
						fileId, processedJob.getJobState(), processedJob.getExceptionInfo()));
			}			
						
			
			// Example 3: stream job and get ID back (JobFile list can be local file paths)
			// -------------------------------------------------------------------------------
			fileId = client.streamJob(settings.getRepositoryName(), inputPayload);
			
			// 3.0 get status and check for completion
			OrchestrationFileStatusResponse orchestrationFileStatusResponse = client.getOrchestrationStatus(fileId);
			System.out.println(String.format("Is complete: %s (status: %s)", Common.isOrchestrationComplete(orchestrationFileStatusResponse)));
			
			// 3.1 wait for completion or timeout
			processedJob = client.waitForJobToProcess(fileId, settings.getDefaultTimeout(),
					settings.getDefaultPollingInterval());
			
			if (processedJob.isSuccessful()) {
				// 3.2 download output and override output filename
				File downloadDirectory = new File("c:\\temp\\Adlib");
				File downloadedFile = client.downloadLibraryRendition(processedJob, downloadDirectory, RenditionType.PDF, DownloadFileNamingMode.REPLACE_EXTENSION, null)
						.get(0);
				System.out.println(String.format("File ID: %s; Job status: %s; Output file name: %s", 
						fileId, processedJob.getJobState(), downloadedFile.getName()));
			} else {
				System.out.println(String.format("File ID: %s; Job status: %s; Error: %s", 
						fileId, processedJob.getJobState(), processedJob.getExceptionInfo()));
			}
			
			// Example 4: stream job synchronously (JobFile list can be local file paths)
			// -------------------------------------------------------------------------------
			processedJob = client.streamSynchronousJob(
							settings.getRepositoryName()
							, inputPayload
							, settings.getDefaultTimeout()
							, settings.getDefaultPollingInterval());
			
			if (processedJob.isSuccessful()) {
				// 4.2 download output and override file name
				File downloadDirectory = new File("c:\\temp\\Adlib");
				String fileNameOverride = "MyOutput.pdf";
				File downloadedFile = client.downloadLibraryRendition(processedJob, 
						downloadDirectory, 
						RenditionType.PDF, 
						DownloadFileNamingMode.OVERRIDE, 
						fileNameOverride)
						.get(0);
				System.out.println(String.format("File ID: %s; Job status: %s; Output file name: %s", 
						fileId, processedJob.getJobState(), downloadedFile.getName()));
			} else {
				System.out.println(String.format("File ID: %s; Job status: %s; Error: %s", 
						fileId, processedJob.getJobState(), processedJob.getExceptionInfo()));
			}			
		}
		catch (Exception e) {
			e.printStackTrace(System.err);
			System.err.println(Common.getFriendlyError(e));
			System.exit(1);
		}
	}
}
