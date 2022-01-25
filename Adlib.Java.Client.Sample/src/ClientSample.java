import java.io.Console;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import com.adlibsoftware.client.ArrayOfMetadataItem;
import com.adlibsoftware.client.ArrayOflong;
import com.adlibsoftware.client.HashAlgorithm;
import com.adlibsoftware.client.JobFile;
import com.adlibsoftware.client.MetadataItem;
import com.adlibsoftware.client.MetadataType;
import com.adlibsoftware.client.Payload;
import com.adlibsoftware.client.RenditionType;
import com.adlibsoftware.exceptions.AdlibTimeoutException;
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
	// Sample secret key for this project (can be stored in keystore or database, etc.)
	private static final char[] AES_SECRET_KEY = new char[] { 'c', 'h', '@', 'n', 'g', '3', 't', 'h', '1', 's' };

	private static String propertiesPath = "ClientSampleSettings.properties";
	
	private static Object locker = new Object();

	
	public static void main(String[] args) {
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
			
			boolean isStreaming = settings.isStreaming();
			boolean isSynchronous = settings.isSynchronous();

			List<Payload> inputPayloadList = new ArrayList<Payload>();
			System.out.println("Making Payloads based on Sample Client Settings...");
			makePayloads(inputPayloadList);
			File outputDirectoryRoot = new File(clientSampleSettings.getString("outputDirectory"));

			System.out.println("Initializing Job Management Service Client with following properties:"
					+ String.format("\r\n\tAuth URL: %s\r\n\tJMS URL: %s\r\n\tUsername: %s\r\n\tRepository: %s\r\n\tLocation: %s\r\n\tStreaming: %s\r\n\tSyncrhronous: %s\r\n\tPayload count: %s"
							, settings.getTokenServiceUrl()
							, settings.getJobManagementServiceWsdlUrl()
							, settings.getTokenServiceUsername()
							, settings.getRepositoryName()
							, settings.getLocationName()
							, isStreaming
							, isSynchronous
							, inputPayloadList.size()));
			
			
			client = new JobManagementServiceClient(settings, true);
			
			// final list of processed jobs
			ConcurrentLinkedQueue<ProcessedJobResponse> processedJobs = null;
			
			int threadCount = clientSampleSettings.getInt("clientSampleThreadCount", 1);
			if (isSynchronous && threadCount == 1 && inputPayloadList.size() > 1) {
				System.out.println("WARNING: It is not recommended to submit syncrhonously with only 1 thread");
			}
			
			if (!isStreaming) {
				String fileShare = clientSampleSettings.getString("inputPayloadShareDirectory");
				System.out.println(String.format("Validating file share location: %s", fileShare));
				validateFileShare(fileShare);
			}
			
			// start batch timer
			LocalDateTime started = LocalDateTime.now();
			
			if (isStreaming && isSynchronous) {
				// Sample streaming synchronous
				processedJobs = executeStreamingSync(settings, inputPayloadList, outputDirectoryRoot);
			} else if (isStreaming && !isSynchronous) {
				// Sample code for streaming asynchronously
				processedJobs = executeStreamingAsync(settings, inputPayloadList, outputDirectoryRoot);
			} else if (!isStreaming && isSynchronous) {
				// Sample file reference (on-premise) snyc
				processedJobs = executeFileReferenceSync(settings, inputPayloadList, outputDirectoryRoot);
			} else if (!isStreaming && !isSynchronous) {
				// Sample file reference (on-premise) async
				processedJobs = executeFileReferenceAsync(settings, inputPayloadList, outputDirectoryRoot);
			}
			
			Duration batchDuration = Duration.between(started, LocalDateTime.now());
			long durationInMilliseconds = Math.abs(batchDuration.toMillis());

			System.out.println(String.format("Successfully ran sample batch of %s job(s) in %s seconds with streaming %s, synchronous %s, %s thread(s)"
					, processedJobs.size()
					, durationInMilliseconds / 1000.0
					, settings.isStreaming() ? "on" : "off"
					, settings.isSynchronous() ? "on" : "off"
						, clientSampleSettings.getInt("clientSampleThreadCount", 1)));
			
			System.out.println("Exiting...");
			System.exit(0);

		} catch (Exception e) {
			e.printStackTrace(System.err);
			// showing how to reveal friendly error (if applicable)
			System.err.println(Common.getFriendlyError(e));
			System.out.println("Exiting...");
			System.exit(1);
		}
	}


	private static void validateFileShare(String fileShare) throws FileNotFoundException {
		File inputFileShare = new File(fileShare);
		boolean isValid = true;
		File currentFolder = new File(inputFileShare.getPath());
		
		isValid = Files.exists(Paths.get(currentFolder.getPath()));
		while (!isValid && currentFolder.getParentFile() != null) {
			currentFolder = currentFolder.getParentFile();
			// do not test root
			if (currentFolder.getParentFile() != null && currentFolder.getParent().length() > 2) {
				isValid = Files.exists(Paths.get(currentFolder.getPath()));
			}
		}
		if (!isValid) {
			throw new FileNotFoundException(String.format("Could not access file share: %s", inputFileShare));
		}
	}


	private static ConcurrentLinkedQueue<ProcessedJobResponse> executeFileReferenceAsync(Settings settings,
			List<Payload> inputPayloadList, File outputDirectoryRoot) throws Exception {
		int threadCount = clientSampleSettings.getInt("clientSampleThreadCount", 1);
		
		ExecutorService executorService = null;
		
		if (threadCount > 1) {
			executorService = Executors.newFixedThreadPool(threadCount);
		}

		final ArrayOflong fileIds = new ArrayOflong();
		final ConcurrentLinkedQueue<ProcessedJobResponse> processedJobs = new ConcurrentLinkedQueue<ProcessedJobResponse>();
		
		File inputFileShare = new File(clientSampleSettings.getString("inputPayloadShareDirectory"));
		System.out.println("Copying local files to file share...");
		copyPayloadFilesToAdlibShare(inputPayloadList, inputFileShare);
		
		int i = 0;
		for (Payload inputPayload : inputPayloadList) {
			i++;
			// Submit job and only return once it's complete
			System.out.println(String.format("Synchronously submitting job #%s of %s", i, inputPayloadList.size()));
			File firstFile = new File(inputPayload.getFiles().getJobFile().get(0).getPath());
			if (executorService != null) {
				executorService.execute(new Runnable() {
					public void run() {
						submitFileReferencePayload(settings, fileIds, inputPayload, firstFile);						
					}					
				});
			} else {
				submitFileReferencePayload(settings, fileIds, inputPayload, firstFile);		
			}				
		}

		if (executorService != null) {
			executorService.shutdown();
			boolean timedOut = !executorService.awaitTermination(settings.getDefaultTimeout().toMinutes(), TimeUnit.MINUTES);

			if (timedOut) {
				throw new TimeoutException("Operation timed out");
			}
			// reset
			executorService = Executors.newFixedThreadPool(threadCount);
		}
		
		if (fileIds.getLong().size() == 0) {
			throw new Exception("Could not submit any files (see previous error)");
		}
		
		System.out.println(String.format("All %s file reference jobs submitted, waiting up to %s seconds for completion of all jobs...",
				fileIds.getLong().size(), settings.getDefaultTimeout().toMillis() / 1000.0));
	
		// If threaded, add fileIds to thread pool to have them all call/wait in own thread
		if (executorService != null) {
			for (long fileId : fileIds.getLong()) {
				executorService.execute(new Runnable() {
					public void run() {
						try {
							System.out.println(String.format("Waiting for FileID %s to finish...", fileId));
							processedJobs.add(client.waitForJobToProcess(fileId, settings.getDefaultTimeout(), settings.getDefaultPollingInterval()));
						} catch (AdlibTimeoutException e) {
							System.err.println(String.format("Operation Timed out for FileId %s", e.toString(), e.getId()));
						} catch (Exception e) {
							System.err.println(String.format("Unknown error: %s", e.toString()));
						}
					}					
				});
			}
			
		} else {
			processedJobs.addAll(client.waitForJobsToProcess(fileIds, settings.getDefaultTimeout(),
					settings.getDefaultPollingInterval()));
		}

		copyProcessedJobFilesAndSetOutputFileNames(executorService, processedJobs, outputDirectoryRoot, settings);
		return processedJobs;
		
	}


	private static ConcurrentLinkedQueue<ProcessedJobResponse> executeFileReferenceSync(Settings settings, List<Payload> inputPayloadList,
			File outputDirectoryRoot) throws Exception {
		int threadCount = clientSampleSettings.getInt("clientSampleThreadCount", 1);
		
		ExecutorService executorService = null;
		
		if (threadCount > 1) {
			executorService = Executors.newFixedThreadPool(threadCount);
		}

		final ConcurrentLinkedQueue<ProcessedJobResponse> processedJobs = new ConcurrentLinkedQueue<ProcessedJobResponse>();
		
		File inputFileShare = new File(clientSampleSettings.getString("inputPayloadShareDirectory"));
		System.out.println("Copying local files to file share...");
		copyPayloadFilesToAdlibShare(inputPayloadList, inputFileShare);
		
		int i = 0;
		for (Payload inputPayload : inputPayloadList) {
			i++;
			// Submit job and only return once it's complete
			System.out.println(String.format("Synchronously submitting job #%s of %s", i, inputPayloadList.size()));
			File firstFile = new File(inputPayload.getFiles().getJobFile().get(0).getPath());
			if (executorService != null) {
				executorService.execute(new Runnable() {
					public void run() {
						submitPayloadSynchronously(settings, processedJobs, inputPayload, firstFile);						
					}					
				});
			} else {
				submitPayloadSynchronously(settings, processedJobs, inputPayload, firstFile);
			}				
		}

		if (executorService != null) {
			executorService.shutdown();
			boolean timedOut = !executorService.awaitTermination(settings.getDefaultTimeout().toMinutes(), TimeUnit.MINUTES);

			if (timedOut) {
				throw new TimeoutException("Operation timed out");
			}
			// reset
			executorService = Executors.newFixedThreadPool(threadCount);
		}
		
		if (processedJobs.size() == 0) {
			throw new Exception("Could not submit any files (see previous error)");
		}

		copyProcessedJobFilesAndSetOutputFileNames(executorService, processedJobs, outputDirectoryRoot, settings);
		return processedJobs;
	}


	private static ConcurrentLinkedQueue<ProcessedJobResponse> executeStreamingSync(Settings settings, List<Payload> inputPayloadList, File outputDirectoryRoot) throws Exception {

		int threadCount = clientSampleSettings.getInt("clientSampleThreadCount", 1);
		
		ExecutorService executorService = null;
		
		if (threadCount > 1) {
			executorService = Executors.newFixedThreadPool(threadCount);
		}
		final ConcurrentLinkedQueue<ProcessedJobResponse> processedJobs = new ConcurrentLinkedQueue<ProcessedJobResponse>();
		
		int i = 0;
		for (Payload inputPayload : inputPayloadList) {
			i++;
			// Submit job and only return once it's complete
			System.out.println(String.format("Synchronously streaming job #%s of %s", i, inputPayloadList.size()));
			
			if (executorService != null) {
				executorService.execute(new Runnable() {
					public void run() {
						streamPayloadSynchronously(settings, processedJobs, inputPayload);
					}					
				});
			} else {
				streamPayloadSynchronously(settings, processedJobs, inputPayload);
			}				
		}

		if (executorService != null) {
			executorService.shutdown();
			boolean timedOut = !executorService.awaitTermination(settings.getDefaultTimeout().toMinutes(), TimeUnit.MINUTES);

			if (timedOut) {
				throw new TimeoutException("Operation timed out");
			}
			// reset
			executorService = Executors.newFixedThreadPool(threadCount);
		}
		
		if (processedJobs.size() == 0) {
			throw new Exception("Could not submit any files (see previous error)");
		}

		downloadProcessedJobFilesAndSetOutputFileNames(executorService, settings, processedJobs, outputDirectoryRoot);
		
		return processedJobs;
	}
	
	private static ConcurrentLinkedQueue<ProcessedJobResponse> executeStreamingAsync(Settings settings, List<Payload> inputPayloadList, File outputDirectoryRoot) throws Exception {
		int threadCount = clientSampleSettings.getInt("clientSampleThreadCount", 1);
		
		ExecutorService executorService = null;
		
		if (threadCount > 1) {
			executorService = Executors.newFixedThreadPool(threadCount);
		}

		final ArrayOflong fileIds = new ArrayOflong();
		final ConcurrentLinkedQueue<ProcessedJobResponse> processedJobs = new ConcurrentLinkedQueue<ProcessedJobResponse>();
		
		int i = 0;
		for (Payload inputPayload : inputPayloadList) {
			i++;
			// Submit job and only return once it's complete
			System.out.println(String.format("Submitting job #%s of %s", i, inputPayloadList.size()));
			
			if (executorService != null) {
				executorService.execute(new Runnable() {
					public void run() {
						streamPayloadAsynchronously(settings, fileIds, inputPayload);
					}					
				});
			} else {
				streamPayloadAsynchronously(settings, fileIds, inputPayload);
			}				
		}

		if (executorService != null) {
			executorService.shutdown();
			boolean timedOut = !executorService.awaitTermination(settings.getDefaultTimeout().toMinutes(), TimeUnit.MINUTES);

			if (timedOut) {
				throw new TimeoutException("Operation timed out");
			}
			// reset
			executorService = Executors.newFixedThreadPool(threadCount);
		}
		
		if (fileIds.getLong().size() == 0) {
			throw new Exception("Could not submit any files (see previous error)");
		}
		
		System.out.println(String.format("All %s jobs submitted, waiting up to %s seconds for completion of all jobs...",
					fileIds.getLong().size(), settings.getDefaultTimeout().toMillis() / 1000.0));
		
		// If threaded, add fileIds to thread pool to have them all call/wait in own thread
		if (executorService != null) {
			for (long fileId : fileIds.getLong()) {
				executorService.execute(new Runnable() {
					public void run() {
						try {
							System.out.println(String.format("Waiting for FileID %s to finish...", fileId));
							processedJobs.add(client.waitForJobToProcess(fileId, settings.getDefaultTimeout(), settings.getDefaultPollingInterval()));
						} catch (AdlibTimeoutException e) {
							System.err.println(String.format("Operation Timed out for FileId %s", e.toString(), e.getId()));
						} catch (Exception e) {
							System.err.println(String.format("Unknown error: %s", e.toString()));
						}
					}					
				});
			}
			
		} else {
			processedJobs.addAll(client.waitForJobsToProcess(fileIds, settings.getDefaultTimeout(),
					settings.getDefaultPollingInterval()));
		}	
		
		downloadProcessedJobFilesAndSetOutputFileNames(executorService, settings, processedJobs, outputDirectoryRoot);
		
		return processedJobs;
		
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

	private static void downloadProcessedJobFilesAndSetOutputFileNames(ExecutorService executorService, 
			Settings settings, 
			ConcurrentLinkedQueue<ProcessedJobResponse> processedJobs,
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
						downloadFile(processedJob, outputDirectoryRoot, settings);
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

	private static void downloadFile(ProcessedJobResponse processedJob, File outputDirectoryRoot, Settings settings) {
		try {
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
		} catch (Exception e) {
			System.err.println(String.format("Error downloading file ID %s: %s)", processedJob.getFileId(), e.toString()));
		}
		
	}

	private static void copyProcessedJobFilesAndSetOutputFileNames(ExecutorService executorService, ConcurrentLinkedQueue<ProcessedJobResponse> processedJobs,
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

		for (ProcessedJobResponse completedJob : processedJobs) {

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
						handleCopyOutput(outputDirectoryRoot, completedJob);						
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

	private static void handleCopyOutput(File outputDirectoryRoot, ProcessedJobResponse processedJob) {
		try {
		File payloadSubFolder = new File(outputDirectoryRoot, String.valueOf(processedJob.getFileId()));
		FileUtils.forceMkdir(payloadSubFolder);

		for (JobFile jobFile : processedJob.getOutputPayload().getFiles().getJobFile()) {
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
				processedJob.getOutputPayload().getFiles().getJobFile().size(), payloadSubFolder,
				processedJob.getFileId(), 
				processedJob.getOutputPayload().getFiles().getJobFile().get(0).getPath()));
		
		} catch (Exception e) {
			System.err.println(String.format("Error copying file ID %s: %s)", processedJob.getFileId(), e.toString()));
		}
	}

	private static void makePayloads(List<Payload> payloadList) throws IOException {
		ArrayOfMetadataItem payloadMetadata = getPayloadMetadataFromProperties();
		// get input directories (each sub-directory of files is a payload/job)
		File[] directories = new File(clientSampleSettings.getString("inputDirectory")).listFiles();
		for (File folder : directories) {
			if (!folder.isDirectory()) {
				continue;
			}
			File[] files = folder.listFiles();
			Payload inputPayload = Common.makePayload(files, payloadMetadata, true);				
			payloadList.add(inputPayload);
		}
		
		if (payloadList.isEmpty()) {
			System.out.println("WARNING: No sub-folders found to get files from, will be submitting a metadata-only job");
			// make a dummy payload without files
			Payload inputPayload = new Payload();
			inputPayload.setMetadata(payloadMetadata);
			payloadList.add(inputPayload);
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


		readPropertiesFile();

		String tokenUrlPattern = clientSampleSettings.getString("tokenServiceUrl", "https://${adlibServerFullyQualifiedName}:8088/connect/token");
		String jmsWsdlPattern = clientSampleSettings.getString("jobManagementServiceUrl", "https://${adlibServerFullyQualifiedName}:55583/Adlib/Services/JobManagement.svc?wsdl");
		
		if (clientSampleSettings.getString("adlibServerFullyQualifiedName").equals("adlibServerName.domain.com")) {
			throw new IllegalArgumentException("adlibServerFullyQualifiedName in settings file must be changed from default of: adlibServerName.domain.com");
		}
		if (clientSampleSettings.getString("tokenServiceEncryptedPassword").equals("ChangeThis")) {
			throw new IllegalArgumentException("tokenServiceEncryptedPassword in settings file must be changed from default of: ChangeThis");
		}

		String tokenUrl = tokenUrlPattern.replace("${adlibServerFullyQualifiedName}",
				clientSampleSettings.getString("adlibServerFullyQualifiedName"));
		String jmsWsdl = jmsWsdlPattern.replace("${adlibServerFullyQualifiedName}",
				clientSampleSettings.getString("adlibServerFullyQualifiedName"));
		settings.setRepositoryName(clientSampleSettings.getString("repositoryName"));
		settings.setLocationName(clientSampleSettings.getString("locationName"));
		
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

		System.out.println("Checking if password in settings is encrypted...");
		// If this fails, the secret key has changed or it's not encrypted or password is empty
		if (AES.decrypt(settings.getTokenServiceEncryptedPassword()) == null) {
			System.out.println("Encrypting password for first time (one-time action)...");
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
				e.printStackTrace();
			}
		}
	}

	private static String getPasswordFromConsole(Settings settings) {
		Console console = System.console();
		if (console == null) {
			//System.out.println("Couldn't get Console instance");
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

	private static boolean streamPayloadSynchronously(Settings settings,
			final ConcurrentLinkedQueue<ProcessedJobResponse> processedJobs, Payload inputPayload) {
		File firstFile = new File(inputPayload.getFiles().getJobFile().get(0).getPath());
		System.out.println(String.format("Streaming job synchronously (first file name: %s)...", firstFile.getName()));
		try {
			ProcessedJobResponse response = client.streamSynchronousJob(settings.getRepositoryName(), inputPayload,
					settings.getDefaultTimeout(), settings.getDefaultPollingInterval());
			processedJobs.add(response);
			return true;
		} catch (AdlibTimeoutException e) {
			System.err.println(String.format("Operation Timed out for FileId %s", e.toString(), e.getId()));
		} catch (Exception e) {
			System.err.println(String.format("Unknown error: %s", e.toString()));
		}
		return false;
	}

	private static boolean streamPayloadAsynchronously(Settings settings, final ArrayOflong fileIds, Payload inputPayload) {
		File firstFile = new File(inputPayload.getFiles().getJobFile().get(0).getPath());
		long fileId = -1;
		try {
			fileId = client.streamJob(settings.getRepositoryName(), inputPayload);
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		System.out.println(String.format("Successfully streamed/began job with File ID %s (first file name %s)...", fileId, firstFile.getName()));
		synchronized  (locker) {
			fileIds.getLong().add(fileId);
		}
		return true;
	}
	


	private static boolean submitPayloadSynchronously(Settings settings,
			final ConcurrentLinkedQueue<ProcessedJobResponse> processedJobs, Payload inputPayload, File firstFile) {
		System.out.println(String.format("Submitting job synchronously (first file name %s)...", firstFile.getName()));
		ProcessedJobResponse response;
		try {
			response = client.streamSynchronousJob(settings.getRepositoryName(), inputPayload,
					settings.getDefaultTimeout(), settings.getDefaultPollingInterval());
			processedJobs.add(response);
			return true;
		} catch (AdlibTimeoutException e) {
			System.err.println(String.format("Operation Timed out for FileId %s", e.toString(), e.getId()));
		} catch (Exception e) {
			System.err.println(String.format("Unknown error: %s", e.toString()));
		}
		return false;
	}


	private static boolean submitFileReferencePayload(Settings settings, final ArrayOflong fileIds, Payload inputPayload,
			File firstFile) {
		long fileId;
		try {
			fileId = client.submitJob(settings.getRepositoryName(), inputPayload);
			System.out.println(String.format("Successfully submitted job with File ID %s (first file name %s)...", fileId, firstFile.getName()));
			synchronized  (locker) {
				fileIds.getLong().add(fileId);
			}
			return true;
		} catch (AdlibTimeoutException e) {
			System.err.println(String.format("Operation Timed out for FileId %s", e.toString(), e.getId()));
		} catch (Exception e) {
			System.err.println(String.format("Unknown error: %s", e.toString()));
		}
		return false;
	}
	
	
}
