package com.synopsys.integration.detect.lifecycle.run.step;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.synopsys.blackduck.upload.client.UploaderConfig;
import com.synopsys.blackduck.upload.client.uploaders.ContainerUploader;
import com.synopsys.blackduck.upload.client.uploaders.UploaderFactory;
import com.synopsys.blackduck.upload.rest.status.DefaultUploadStatus;
import com.synopsys.integration.blackduck.version.BlackDuckVersion;
import com.synopsys.integration.detect.lifecycle.OperationException;
import com.synopsys.integration.detect.lifecycle.run.data.BlackDuckRunData;
import com.synopsys.integration.detect.lifecycle.run.operation.OperationRunner;
import com.synopsys.integration.detect.util.bdio.protobuf.DetectProtobufBdioHeaderUtil;
import com.synopsys.integration.detect.workflow.codelocation.CodeLocationNameManager;
import com.synopsys.integration.exception.IntegrationException;
import com.synopsys.integration.log.Slf4jIntLogger;
import com.synopsys.integration.rest.response.Response;
import com.synopsys.integration.util.NameVersion;

import com.google.gson.JsonObject;

public class ContainerScanStepRunner {

    private final OperationRunner operationRunner;
    private UUID scanId;
    private String scanType = "CONTAINER";
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final NameVersion projectNameVersion;
    private final String projectGroupName;
    private final BlackDuckRunData blackDuckRunData;
    private final File binaryRunDirectory;
    private final File containerImage;
    private final Long containerImageSizeInBytes;
    private String codeLocationName;
    private UploaderFactory uploadFactory;
    private static final BlackDuckVersion MIN_BLACK_DUCK_VERSION = new BlackDuckVersion(2023, 10, 0);
    private static final String STORAGE_CONTAINERS_ENDPOINT = "/api/storage/containers/";
    private static final String STORAGE_IMAGE_CONTENT_TYPE = "application/vnd.blackducksoftware.container-scan-data-1+octet-stream";
    private static final String STORAGE_IMAGE_METADATA_CONTENT_TYPE = "application/vnd.blackducksoftware.container-scan-message-1+json";

    public ContainerScanStepRunner(OperationRunner operationRunner, NameVersion projectNameVersion, BlackDuckRunData blackDuckRunData, Gson gson)
        throws IntegrationException, OperationException {
        this.operationRunner = operationRunner;
        this.projectNameVersion = projectNameVersion;
        this.blackDuckRunData = blackDuckRunData;
        binaryRunDirectory = operationRunner.getDirectoryManager().getBinaryOutputDirectory();
        if (binaryRunDirectory == null || !binaryRunDirectory.exists()) {
            throw new IntegrationException("Binary run directory does not exist.");
        }
        projectGroupName = operationRunner.calculateProjectGroupOptions().getProjectGroup();
        containerImage = operationRunner.getContainerScanImage(gson, binaryRunDirectory);
        containerImageSizeInBytes = containerImage != null && containerImage.exists() ? containerImage.length() : 0;
        
        initContainerUploadFactory(blackDuckRunData);
    }

    public Optional<UUID> invokeContainerScanningWorkflow() {
        try {
            logger.debug("Determining if configuration is valid to run a container scan.");
            if (!isContainerScanEligible()) {
                logger.info("No container.scan.file.path property was provided. Skipping container scan.");
                return Optional.ofNullable(scanId);
            }
            if (!isBlackDuckVersionValid()) {
                String minBlackDuckVersion = String.join(".",
                    Integer.toString(MIN_BLACK_DUCK_VERSION.getMajor()),
                    Integer.toString(MIN_BLACK_DUCK_VERSION.getMinor()),
                    Integer.toString(MIN_BLACK_DUCK_VERSION.getPatch())
                );
                throw new IntegrationException("Container scan is only supported with BlackDuck version " + minBlackDuckVersion + " or greater. Container scan could not be run.");
            }
            if (!isContainerImageResolved()) {
                throw new IOException("Container image file path not resolved or file could not be downloaded. Container scan could not be run.");
            }

            codeLocationName = createContainerScanCodeLocationName();
            initiateScan();
            logger.info("Container scan initiated. Uploading container scan image.");
            
            // Start new area
            // TODO need a version check, seem to have BlackDuck version information see line 63, so only 
            // do new stuff on 2024.10 or later
            //uploadImageToStorageService();
            
            multiPartUploadImage();
            
            // End new area
            
            uploadImageMetadataToStorageService();
            operationRunner.publishContainerSuccess();
            logger.info("Container scan image uploaded successfully.");
        } catch (IntegrationException | IOException | OperationException e) {
            operationRunner.publishContainerFailure(e);
            return Optional.empty();
        }
        return Optional.ofNullable(scanId);
    }

    public String getCodeLocationName() {
        return codeLocationName;
    }

    private boolean isContainerImageResolved() {
        return containerImage != null && containerImage.exists();
    }

    private boolean isContainerScanEligible() {
        return operationRunner.getContainerScanFilePath().isPresent();
    }

    private boolean isBlackDuckVersionValid() {
        Optional<BlackDuckVersion> blackDuckVersion = blackDuckRunData.getBlackDuckServerVersion();
        return blackDuckVersion.isPresent() && blackDuckVersion.get().isAtLeast(MIN_BLACK_DUCK_VERSION);
    }

    private String createContainerScanCodeLocationName() {
        CodeLocationNameManager codeLocationNameManager = operationRunner.getCodeLocationNameManager();
        return codeLocationNameManager.createContainerScanCodeLocationName(containerImage, projectNameVersion.getName(), projectNameVersion.getVersion());
    }

    private void initiateScan() throws IOException, IntegrationException, OperationException {
        DetectProtobufBdioHeaderUtil detectProtobufBdioHeaderUtil = new DetectProtobufBdioHeaderUtil(
            UUID.randomUUID().toString(),
            scanType,
            projectNameVersion,
            projectGroupName,
            codeLocationName,
            containerImageSizeInBytes);
        File bdioHeaderFile = detectProtobufBdioHeaderUtil.createProtobufBdioHeader(binaryRunDirectory);
        String operationName = "Upload Container Scan BDIO Header to Initiate Scan";
        scanId = operationRunner.uploadBdioHeaderToInitiateScan(blackDuckRunData, bdioHeaderFile, operationName);
        if (scanId == null) {
            logger.warn("Scan ID was not found in the response from the server.");
            throw new IntegrationException("Scan ID was not found in the response from the server.");
        }
        String scanIdString = scanId.toString();
        logger.debug("Scan initiated with scan service. Scan ID received: {}", scanIdString);
    }

    private void uploadImageToStorageService() throws IOException, IntegrationException, OperationException {
        String storageServiceEndpoint = String.join("", STORAGE_CONTAINERS_ENDPOINT, scanId.toString());
        String operationName = "Upload Container Scan Image";
        logger.debug("Uploading container image artifact to storage endpoint: {}", storageServiceEndpoint);

        try (Response response = operationRunner.uploadFileToStorageService(
            blackDuckRunData,
            storageServiceEndpoint,
            containerImage,
            STORAGE_IMAGE_CONTENT_TYPE,
            operationName
        )
        ) {
            if (response.isStatusCodeSuccess()) {
                logger.debug("Container scan image uploaded to storage service.");
            } else {
                logger.trace("Unable to upload container image. {} {}", response.getStatusCode(), response.getStatusMessage());
                throw new IntegrationException(String.join(" ", "Unable to upload container image. Response code:", String.valueOf(response.getStatusCode()), response.getStatusMessage()));
            }
        }
    }
    
    private void multiPartUploadImage() throws IntegrationException {
        String storageServiceEndpoint = String.join("", STORAGE_CONTAINERS_ENDPOINT, scanId.toString());
        
        // Create an instance of the ContainerUploader using the UploaderFactory::createContainerUploader passing 
        // in the url prefix from earlier similar to how you do it with the BinaryUploader, but without the requirement 
        // of binaryScanRequestData
        logger.debug("Performing multipart container image upload to storage endpoint: {}", storageServiceEndpoint);
        ContainerUploader containerUploader = uploadFactory.createContainerUploader(storageServiceEndpoint);
        
        try {
            DefaultUploadStatus status = containerUploader.upload(containerImage.toPath());
            
            if (status.isError()) {
                handleUploadError(status);
            }
            
            logger.debug("Multipart container scan image uploaded to storage service.");
        } catch (IOException | IntegrationException e) {
            logger.trace("Unable to upload multipart container image.");
            throw new IntegrationException("Unable to upload multipart container image. " + e.getMessage());
        }     
    }

    private void handleUploadError(DefaultUploadStatus status) {
        // TODO Auto-generated method stub
        
    }

    private void uploadImageMetadataToStorageService() throws IntegrationException, IOException, OperationException {
        String storageServiceEndpoint = String.join("", STORAGE_CONTAINERS_ENDPOINT, scanId.toString(), "/message");
        String operationName = "Upload Container Scan Image Metadata JSON";
        logger.debug("Uploading container image metadata to storage endpoint: {}", storageServiceEndpoint);

        JsonObject imageMetadataObject = operationRunner.createContainerScanImageMetadata(scanId, projectNameVersion);

        try (Response response = operationRunner.uploadJsonToStorageService(
            blackDuckRunData,
            storageServiceEndpoint,
            imageMetadataObject.toString(),
            STORAGE_IMAGE_METADATA_CONTENT_TYPE,
            operationName
        )
        ) {
            if (response.isStatusCodeSuccess()) {
                logger.debug("Container scan image metadata uploaded to storage service.");
            } else {
                logger.trace("Unable to upload container image metadata." + response.getStatusCode() + " " + response.getStatusMessage());
                throw new IntegrationException(String.join(" ", "Unable to upload container image metadata. Response code:", String.valueOf(response.getStatusCode()), response.getStatusMessage()));
            }
        }
    }
    
    private void initContainerUploadFactory(BlackDuckRunData blackDuckRunData) throws IntegrationException {
        UploaderConfig.Builder uploaderConfigBuilder =  UploaderConfig.createConfigFromEnvironment(blackDuckRunData.getBlackDuckServerConfig().getProxyInfo())
            .setBlackDuckTimeoutInSeconds(blackDuckRunData.getBlackDuckServerConfig().getTimeout())
            .setMultipartUploadTimeoutInMinutes(blackDuckRunData.getBlackDuckServerConfig().getTimeout() /  60)
            .setAlwaysTrustServerCertificate(blackDuckRunData.getBlackDuckServerConfig().isAlwaysTrustServerCertificate())
            .setBlackDuckUrl(blackDuckRunData.getBlackDuckServerConfig().getBlackDuckUrl())
            .setApiToken(blackDuckRunData.getBlackDuckServerConfig().getApiToken().get());
        
        UploaderConfig uploaderConfig = uploaderConfigBuilder.build();
        uploadFactory = new UploaderFactory(uploaderConfig, new Slf4jIntLogger(logger), new Gson());
    }
}
