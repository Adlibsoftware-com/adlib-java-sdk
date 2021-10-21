import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.configuration2.FileBasedConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import com.adlibsoftware.authorize.AES;
import com.adlibsoftware.client.ArrayOfJobFile;
import com.adlibsoftware.client.ArrayOfMetadataItem;
import com.adlibsoftware.client.ArrayOflong;
import com.adlibsoftware.client.HashAlgorithm;
import com.adlibsoftware.client.JobFile;
import com.adlibsoftware.client.MetadataItem;
import com.adlibsoftware.client.MetadataType;
import com.adlibsoftware.client.Payload;
import com.adlibsoftware.client.RenditionType;
import com.adlibsoftware.integration.Common;
import com.adlibsoftware.integration.DownloadFileNamingMode;
import com.adlibsoftware.integration.JobManagementServiceClient;
import com.adlibsoftware.integration.ProcessedJobResponse;
import com.adlibsoftware.integration.Settings;

/**
 * @author mmanley
 * This is a Sample Client class for demonstrating integrations to Adlib Elevate Job Management Web Service
 * Disclaimer: This code is provided as-is and can be modified/used in any solution
 */
public class ClientSample {

	private static FileBasedConfiguration clientSampleSettings;
	private static FileBasedConfigurationBuilder<FileBasedConfiguration> builder;

	private static JobManagementServiceClient client;
	// Sample secret key for this project (can be stored in keystore or other way)
	private static final char[] AES_SECRET_KEY = new char[] { 'c', 'h', '@', 'n', 'g', '3', 't', 'h', '1', 's' };

	private static String propertiesPath = "ClientSampleSettings.properties";
	
	private static Object locker = new Object();

	
	public static void main(String[] args) {
		ExecutorService executorService = null;
		try {

			// Optional: This removes the JAX-WS WARN messages
			// which are caused from Adlib Web Service (which uses WS-Addressing, unfamiliar
			// to JAX-WS)
			System.setProperty("java.util.logging.config.file", "logging.properties");

			if (args.length > 0) {
				propertiesPath = args[0];
			}

			Settings settings = new Settings();
			applySettingsFromSampleProperties(settings);

			System.out.println("Initializing Job Management Service Client...");
			client = new JobManagementServiceClient(settings, true);

			int threadCount = clientSampleSettings.getInt("clientSampleThreadCount", 1);
			
			if (threadCount > 1) {
				executorService = Executors.newFixedThreadPool(threadCount);
			}			

			List<Payload> inputPayloadList = new ArrayList<Payload>();
			System.out.println("Making Payloads based on Sample Client Settings...");
			makePayloads(inputPayloadList);

			File outputDirectoryRoot = new File(clientSampleSettings.getString("outputDirectory"));

			if (!settings.isStreaming()) {
				File inputFileShare = new File(clientSampleSettings.getString("inputPayloadShareDirectory"));
				System.out.println("Copying local files to file share...");
				copyPayloadFilesToAdlibShare(inputPayloadList, inputFileShare);
			}

			LocalDateTime started = LocalDateTime.now();
			final ArrayOflong fileIds = new ArrayOflong();
			final List<ProcessedJobResponse> processedJobs = new ArrayList<ProcessedJobResponse>();
			
			int i = 0;
			for (Payload inputPayload : inputPayloadList) {
				i++;
				// Submit job and only return once it's complete
				System.out.println(String.format("Submitting job #%s of %s", i, inputPayloadList.size()));
				
				if (executorService != null) {
					executorService.execute(new Runnable() {
						public void run() {
							processPayload(settings, fileIds, processedJobs, inputPayload);	
						}					
					});
				} else {
					processPayload(settings, fileIds, processedJobs, inputPayload);	
				}				
			}

			if (executorService != null) {
				executorService.shutdown();
				boolean timedOut = !executorService.awaitTermination(settings.getDefaultTimeout().toMinutes(), TimeUnit.MINUTES);
	
				if (timedOut) {
					throw new TimeoutException("Operation timed out");
				}
				executorService = Executors.newFixedThreadPool(threadCount);
			}
			
			if (!settings.isSynchronous()) {
				// Submit job and only return once it's complete
				if (fileIds.getLong().size() == 1) {
					System.out.println("Job submitted, waiting for it to complete...");
				} else {
					System.out.println(String.format("All %s jobs submitted, waiting for them to complete...",
							fileIds.getLong().size()));
				}				
				processedJobs.addAll(client.waitForJobsToProcess(fileIds, settings.getDefaultTimeout(),
						settings.getDefaultPollingInterval()));
			}
			
			if (!settings.isStreaming()) {
				// copy files to output folder and update Payloads
				if (fileIds.getLong().size() == 1) {
					System.out.println(String.format("Job complete, copying output files to output directory...",
							settings.isSynchronous() ? processedJobs.size() : fileIds.getLong().size()));
				} else {
					System.out.println(
							String.format("All %s jobs complete, copying their output files to output directory...",
									settings.isSynchronous() ? processedJobs.size() : fileIds.getLong().size()));
				}

				copyProcessedJobFilesAndSetOutputFileNames(executorService, processedJobs, outputDirectoryRoot, settings);
			} else {
				if (fileIds.getLong().size() == 1) {
					System.out.println(String.format("Job complete, downloading output to output directory...",
							settings.isSynchronous() ? processedJobs.size() : fileIds.getLong().size()));
				} else {
					System.out.println(
							String.format("All %s jobs complete, downloading their output to output directory...",
									settings.isSynchronous() ? processedJobs.size() : fileIds.getLong().size()));
				}
				downloadProcessedJobFilesAndSetOutputFileNames(executorService, settings, processedJobs, outputDirectoryRoot);
			}
			
			Duration batchDuration = Duration.between(started, LocalDateTime.now());

			System.out.println(String.format("Successfully ran sample batch of %s jobs in %s seconds with streaming %s, synchronous %s, %s threads"
					, processedJobs.size()
					, batchDuration.toMillis() / 1000.0
					, settings.isStreaming() ? "on" : "off"
					, settings.isSynchronous() ? "on" : "off"
						, threadCount));
			System.exit(0);

		} catch (Exception e) {
			e.printStackTrace(System.err);
			System.err.println(Common.getFriendlyError(e));
			System.exit(1);
		}
	}
	
	private static void processPayload(Settings settings,
			final ArrayOflong fileIds, final List<ProcessedJobResponse> processedJobs,
			Payload inputPayload) {
		try {
			File firstFile = new File(inputPayload.getFiles().getJobFile().get(0).getPath());
			if (settings.isSynchronous()) {
				ProcessedJobResponse response = null;
				if (settings.isStreaming()) {
					System.out.println(String.format("Streaming job synchronously (first file name %s)...", firstFile.getName()));
					response = client.streamSynchronousJob(settings.getRepositoryName(), inputPayload,
							settings.getDefaultTimeout(), settings.getDefaultPollingInterval());
				} else {
					System.out.println(String.format("Submitting job synchronously (first file name %s)...", firstFile.getName()));
					response = client.submitSynchronousJob(settings.getRepositoryName(), inputPayload,
							settings.getDefaultTimeout(), settings.getDefaultPollingInterval());
				}
				synchronized  (locker) {
					processedJobs.add(response);
				}
				
			} else {
				Long fileId = null;
				if (settings.isStreaming()) {
					fileId = client.streamJob(settings.getRepositoryName(), inputPayload);
					System.out.println(String.format("Successfully streamed/began job with File ID %s (first file name %s)...", fileId, firstFile.getName()));
				} else {
					// submit it and store job id for later
					fileId = client.submitJob(settings.getRepositoryName(), inputPayload);
					System.out.println(String.format("Successfully began job with File ID %s (first file name %s)...", fileId, firstFile.getName()));
				}
				synchronized  (locker) {
					fileIds.getLong().add(fileId);
				}								
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void copyPayloadFilesToAdlibShare(List<Payload> inputPayloadList, File inputShareDirectory)
			throws IOException {
		if (inputShareDirectory.exists()) {
			try {
				FileUtils.deleteDirectory(inputShareDirectory);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		FileUtils.forceMkdir(inputShareDirectory);

		for (int i = 0; i < inputPayloadList.size(); i++) {

			Payload payload = inputPayloadList.get(i);
			File payloadSubFolder = new File(inputShareDirectory, String.valueOf(i));
			FileUtils.forceMkdir(payloadSubFolder);
			for (JobFile jobFile : payload.getFiles().getJobFile()) {

				File localPath = new File(jobFile.getPath());
				File newPath = new File(payloadSubFolder, localPath.getName());
				FileUtils.copyFile(localPath, newPath);
				jobFile.setPath(newPath.getAbsolutePath());
			}
		}
	}

	private static void downloadProcessedJobFilesAndSetOutputFileNames(ExecutorService executorService, Settings settings, List<ProcessedJobResponse> processedJobs,
			File outputDirectoryRoot) throws Exception {
		if (outputDirectoryRoot.exists()) {
			try {
				FileUtils.deleteDirectory(outputDirectoryRoot);
				Thread.sleep(500);
			} catch (IOException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		FileUtils.forceMkdir(outputDirectoryRoot);

		for (ProcessedJobResponse processedJob : processedJobs) {

			if (!processedJob.isSuccessful()) {
				System.out.println(String.format("Job %s not successful, skipping copy...", processedJob.getFileId()));
				continue;
			} else if (processedJob.getOutputPayload().getFiles().getJobFile().size() == 0 && settings.isDownloadJobPayloadOutputFiles()) {
				System.out.println(String.format("Job %s is successful, but has not output files, skipping...",
						processedJob.getFileId()));
				continue;
			} else {
				System.out.println(String.format("Job %s is successful, downloading output...",
						processedJob.getFileId()));
			}

			if (executorService != null) {
				executorService.execute(new Runnable() {
					public void run() {
						try {
							downloadFile(processedJob, outputDirectoryRoot, settings);
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}					
				});
			} else {
				downloadFile(processedJob, outputDirectoryRoot, settings);
			}
		}
		
		if (executorService != null) {
			executorService.shutdown();
			boolean timedOut = !executorService.awaitTermination(settings.getDefaultTimeout().toMinutes(), TimeUnit.MINUTES);

			if (timedOut) {
				throw new TimeoutException("Operation timed out");
			}
		}
	}

	private static void downloadFile(ProcessedJobResponse processedJob, File outputDirectoryRoot, Settings settings) throws Exception {
		File payloadSubFolder = new File(outputDirectoryRoot, String.valueOf(processedJob.getFileId()));
		FileUtils.forceMkdir(payloadSubFolder);

		List<File> filesDownloaded = null;
		if (settings.isDownloadJobPayloadOutputFiles()) {
			filesDownloaded = client.downloadOutputPayloadFiles(processedJob, payloadSubFolder, DownloadFileNamingMode.APPEND_OUTPUT_EXTENSION);
		}
		else {
			filesDownloaded = client.downloadLibraryRendition
				(processedJob, payloadSubFolder, RenditionType.PDF, DownloadFileNamingMode.APPEND_OUTPUT_EXTENSION, null);
		}			

		if (filesDownloaded.size() > 0) {
			System.out.println(String.format("Successfully downloaded %s file(s) to output %s for File ID %s (first file %s) ...",
					filesDownloaded.size(), payloadSubFolder,
					processedJob.getFileId(), filesDownloaded.get(0).getName()));
		} else {
			System.out.println(String.format("No files to download for File ID %s ...",
					filesDownloaded.size(), payloadSubFolder,
					processedJob.getFileId(), filesDownloaded.get(0).getName()));
		}
	}

	private static void copyProcessedJobFilesAndSetOutputFileNames(ExecutorService executorService, List<ProcessedJobResponse> completedJobs,
			File outputDirectoryRoot, Settings settings) throws Exception {
		if (outputDirectoryRoot.exists()) {
			try {
				FileUtils.deleteDirectory(outputDirectoryRoot);
				Thread.sleep(500);
			} catch (IOException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		FileUtils.forceMkdir(outputDirectoryRoot);

		for (ProcessedJobResponse completedJob : completedJobs) {

			if (!completedJob.isSuccessful()) {
				System.out.println(String.format("Job %s not successful, skipping copy...", completedJob.getFileId()));
				continue;
			}
			if (completedJob.getOutputPayload().getFiles().getJobFile().size() == 0) {
				System.out.println(String.format("Job %s is successful, but has not output files, skipping...",
						completedJob.getFileId()));
				continue;
			}
			
			if (executorService != null) {
				executorService.execute(new Runnable() {
					public void run() {
						try {
							handleCopyOutput(outputDirectoryRoot, completedJob);
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}					
				});
			} else {
				handleCopyOutput(outputDirectoryRoot, completedJob);
			}	
		}
		
		if (executorService != null) {
			executorService.shutdown();
			boolean timedOut = !executorService.awaitTermination(settings.getDefaultTimeout().toMinutes(), TimeUnit.MINUTES);

			if (timedOut) {
				throw new TimeoutException("Operation timed out");
			}
		}
	}

	private static void handleCopyOutput(File outputDirectoryRoot, ProcessedJobResponse completedJob)
			throws Exception {
		File payloadSubFolder = new File(outputDirectoryRoot, String.valueOf(completedJob.getFileId()));
		FileUtils.forceMkdir(payloadSubFolder);

		for (JobFile jobFile : completedJob.getOutputPayload().getFiles().getJobFile()) {
			File jobFilePath = new File(jobFile.getPath());
			if (!jobFilePath.exists()) {
				throw new IOException(String.format("Job File '%s' does not exist (it may have been in a temporary work directory, which means workflow must change)", jobFilePath.getAbsolutePath()));
			}
			String outputFileName = jobFilePath.getName();
			// Get original file name that was stored from input metadata
			Object originalFileName = Common.getMetadataValueByName(jobFile.getMetadata(), "OriginalFileName",
					null);
			if (originalFileName != null) {
				outputFileName = originalFileName.toString() + "."
						+ FilenameUtils.getExtension(jobFilePath.getName());
			}

			File newPath = new File(payloadSubFolder, outputFileName);
			FileUtils.copyFile(jobFilePath, newPath);
			jobFile.setPath(newPath.getAbsolutePath());
		}
		
		System.out.println(String.format("Successfully copied %s file(s) to output %s for Job %s (first file %s) ...",
				completedJob.getOutputPayload().getFiles().getJobFile().size(), payloadSubFolder,
				completedJob.getFileId(), 
				completedJob.getOutputPayload().getFiles().getJobFile().get(0).getPath()));
	}

	private static void makePayloads(List<Payload> payloadList) {
		ArrayOfMetadataItem payloadMetadata = getPayloadMetadataFromProperties();
		// get input directories (each sub-directory of files is a payload/job)
		File[] files = new File(clientSampleSettings.getString("inputDirectory")).listFiles();
		if (files == null || files.length == 0) {
			System.out.println("WARNING: No sub-folders found to get files from, will be submitting a no-file job");
			// make a dummy payload without files
			Payload inputPayload = new Payload();
			inputPayload.setMetadata(payloadMetadata);
			payloadList.add(inputPayload);
		} else {
			for (File folder : files) {
				if (!folder.isDirectory()) {
					continue;
				}

				Payload inputPayload = new Payload();
				ArrayOfJobFile arrayOfJobFiles = Common.setInputFiles(folder.listFiles());
				inputPayload.setFiles(arrayOfJobFiles);
				inputPayload.setMetadata(payloadMetadata);
				/*
				 * inputPayload.setFiles(arrayOfJobFiles); for (File inputFile :
				 * folder.listFiles()) { if (inputFile.isDirectory()) { continue; } // Set file
				 * metadata ArrayOfMetadataItem fileMetadata = new ArrayOfMetadataItem();
				 * MetadataItem metadata = new MetadataItem();
				 * metadata.setName("OriginalFileName"); metadata.setValue(inputFile.getName());
				 * metadata.setType(MetadataType.STRING);
				 * fileMetadata.getMetadataItem().add(metadata);
				 * arrayOfJobFiles.getJobFile().add(Common.makeJobFile(inputFile,
				 * fileMetadata));
				 * 
				 * }
				 */
				payloadList.add(inputPayload);
			}
		}
	}

	private static ArrayOfMetadataItem getPayloadMetadataFromProperties() {
		ArrayOfMetadataItem payloadMetadata = new ArrayOfMetadataItem();
		for (Iterator<String> key = clientSampleSettings.getKeys(); key.hasNext();) {
			String name = key.next();
			// now you have name and value
			if (name.startsWith("payloadMetadata")) {
				List<Object> nameValuePair = clientSampleSettings.getList(name);
				MetadataItem metadata = new MetadataItem();
				metadata.setName(nameValuePair.get(0).toString());
				metadata.setValue(nameValuePair.get(1).toString());
				metadata.setType(MetadataType.STRING);
				payloadMetadata.getMetadataItem().add(metadata);
			}
		}
		return payloadMetadata;
	}

	private static void applySettingsFromSampleProperties(Settings settings) throws Exception {

		System.out.println("Loading Sample Client settings...");

		String tokenUrlPattern = "https://[computer]:8088/connect/token";
		String jmsWsdlPattern = "https://[computer]:55583/Adlib/Services/JobManagement.svc?wsdl";

		readPropertiesFile();

		String tokenUrl = tokenUrlPattern.replace("[computer]",
				clientSampleSettings.getString("adlibServerFullyQualifiedName"));
		String jmsWsdl = jmsWsdlPattern.replace("[computer]",
				clientSampleSettings.getString("adlibServerFullyQualifiedName"));
		settings.setRepositoryName(clientSampleSettings.getString("repositoryName"));
		//settings. (clientSampleSettings.getString("repositoryName"));
		settings.setDefaultTimeout(Duration.ofMinutes(clientSampleSettings.getLong("timeoutMinutes")));
		settings.setDefaultPollingInterval(
				Duration.ofMillis(clientSampleSettings.getLong("pollingIntervalMilliseconds")));
		settings.setJobManagementServiceWsdlUrl(new URL(jmsWsdl));
		settings.setTokenServiceUrl(new URL(tokenUrl));
		settings.setTokenServiceUsername(clientSampleSettings.getString("tokenServiceUsername"));
		settings.setTokenServiceEncryptedPassword(clientSampleSettings.getString("tokenServiceEncryptedPassword"));
		settings.setStreamingBufferSizeBytes(clientSampleSettings.getInt("streamingBufferSizeBytes"));
		settings.setDownloadJobPayloadOutputFiles(clientSampleSettings.getBoolean("downloadJobPayloadOutputFiles"));
		settings.setStreaming(clientSampleSettings.getBoolean("streaming"));
		settings.setSynchronous(clientSampleSettings.getBoolean("synchronous"));
		
		HashAlgorithm hashAlgorithm = HashAlgorithm.NONE;		
		String uploadDownloadHashAlgorithm = clientSampleSettings.getString("uploadDownloadHashAlgorithm");
		if (uploadDownloadHashAlgorithm != null && !uploadDownloadHashAlgorithm.equalsIgnoreCase("NONE")) {
			hashAlgorithm = HashAlgorithm.fromValue(uploadDownloadHashAlgorithm.replace("-", ""));
		}		
		settings.setHashAlgorithm(hashAlgorithm);
		settings.setSecretKey(String.valueOf(AES_SECRET_KEY));

		// If this fails, the secret key has changed or it's not encrypted or password
		// is empty
		if (AES.decrypt(settings.getTokenServiceEncryptedPassword()) == null) {
			String plainTextPassword = getPasswordFromConsole(settings);
			String encrypted;
			if (plainTextPassword == null) {
				// Couldn't use Console, let's assume properties has plain text password (first
				// time) and encrypt it
				encrypted = AES.encrypt(settings.getTokenServiceEncryptedPassword());
			} else {
				encrypted = AES.encrypt(plainTextPassword);
			}
			if (encrypted == null) {
				throw new Exception("Could not encrypt password");
			}
			clientSampleSettings.setProperty("tokenServiceEncryptedPassword", encrypted);
			settings.setTokenServiceEncryptedPassword(encrypted);
			try {
				builder.save();
			} catch (ConfigurationException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	private static String getPasswordFromConsole(Settings settings) {
		Console console = System.console();
		if (console == null) {
			System.out.println("Couldn't get Console instance");
			return null;
		}

		String prompt = String.format("Enter Authorization password for %s: ", settings.getTokenServiceUsername());
		String reEnterPrompt = String.format("Re-enter Authorization password for %s: ",
				settings.getTokenServiceUsername());

		char[] passwordArray = console.readPassword(prompt);
		char[] passwordArray2 = console.readPassword(reEnterPrompt);

		while (passwordArray != passwordArray2) {
			console.printf("Passwords do not match");
			passwordArray = console.readPassword(prompt);
			passwordArray2 = console.readPassword(reEnterPrompt);
		}

		return new String(passwordArray);

	}

	public static void readPropertiesFile() {
		try {

			builder = new FileBasedConfigurationBuilder<FileBasedConfiguration>(PropertiesConfiguration.class)
					.configure(
							new Parameters().properties().setListDelimiterHandler(new DefaultListDelimiterHandler(','))
									.setFile(new File(propertiesPath)));

			clientSampleSettings = builder.getConfiguration();
		} catch (ConfigurationException e) {
			e.printStackTrace();
		}
	}
	
	
}
