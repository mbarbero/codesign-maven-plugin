/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.maven.plugins.signing;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;

/**
 * Maven goal that signs build artifacts via the SignPath REST API.
 * <p>
 * The mojo scans the configured build directory using include/exclude glob
 * patterns, submits matching files for signing, polls each request until a final
 * status is reached, and downloads the signed artifact (either in-place or to the
 * configured output directory).
 * <p>
 * API authentication is resolved from plugin configuration, Maven
 * {@code settings.xml}, or the {@code SIGNPATH_API_TOKEN} environment variable.
 */
@Mojo(name = "sign", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class SignMojo extends AbstractMojo {

	private static final String SIGNPATH_API_TOKEN = "SIGNPATH_API_TOKEN";
	private static final String SIGNPATH_SKIP_SIGNING = "SIGNPATH_SKIP_SIGNING";
	
	private SettingsDecrypter settingsDecrypter;

	/**
	 * SignPath organization identifier.
	 * <p>
	 * Required. Mapped to {@code -Dsignpath.organizationId}.
	 */
	@Parameter(property = "signpath.organizationId", required = true)
	private String organizationId;

	/**
	 * SignPath API token provided directly in plugin configuration or as a system
	 * property.
	 * <p>
	 * Optional. Mapped to {@code -Dsignpath.apiToken}. If not provided, token
	 * resolution falls back to {@code settings.xml} and environment variables.
	 */
	@Parameter(property = "signpath.apiToken")
	private String apiToken;

	/**
	 * Maven server ID used to resolve the API token from {@code settings.xml}
	 * server password.
	 * <p>
	 * Optional. Default is {@code signpath}.
	 */
	@Parameter(property = "signpath.serverId", defaultValue = "signpath")
	private String serverId;

	@Parameter(defaultValue = "${session}", readonly = true)
	private MavenSession session;

	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject project;

	/**
	 * SignPath project slug that owns the signing configuration.
	 * <p>
	 * Required. Mapped to {@code -Dsignpath.projectSlug}.
	 */
	@Parameter(property = "signpath.projectSlug", required = true)
	private String projectSlug;

	/**
	 * SignPath signing policy slug used for submitted artifacts.
	 * <p>
	 * Required. Mapped to {@code -Dsignpath.signingPolicySlug}.
	 */
	@Parameter(property = "signpath.signingPolicySlug", required = true)
	private String signingPolicySlug;

	/**
	 * Optional SignPath artifact configuration slug.
	 * <p>
	 * Mapped to {@code -Dsignpath.artifactConfigurationSlug}.
	 */
	@Parameter(property = "signpath.artifactConfigurationSlug")
	private String artifactConfigurationSlug;

	/**
	 * Optional signing request description shown in SignPath.
	 * <p>
	 * Mapped to {@code -Dsignpath.description}.
	 */
	@Parameter(property = "signpath.description")
	private String description;

	/**
	 * SignPath API base URL.
	 * <p>
	 * Optional. Default targets the hosted SignPath API endpoint.
	 */
	@Parameter(property = "signpath.baseUrl", defaultValue = "https://app.signpath.io/Api")
	private String baseUrl;

	/**
	 * Base directory scanned for input artifacts.
	 * <p>
	 * Optional. Defaults to {@code ${project.build.directory}}.
	 */
	@Parameter(defaultValue = "${project.build.directory}")
	private String baseDirectory;

	/**
	 * Include glob patterns used to select files under {@link #baseDirectory}.
	 * <p>
	 * Optional. If empty, no files are selected by the file scan; use
	 * {@link #signProjectArtifact} or {@link #signAttachedArtifacts} to sign
	 * Maven artifacts explicitly.
	 */
	@Parameter
	private String[] includes;

	/**
	 * Exclude glob patterns applied after includes.
	 * <p>
	 * Optional. Has no effect when {@link #includes} is empty, since an empty
	 * {@link #includes} produces no candidates for excludes to filter.
	 */
	@Parameter
	private String[] excludes;

	/**
	 * Optional output directory for signed artifacts.
	 * <p>
	 * If unset, signed files overwrite original files in place.
	 */
	@Parameter(property = "signpath.outputDirectory")
	private String outputDirectory;

	/**
	 * Whether to include the Maven project's main artifact in signing.
	 * <p>
	 * Optional. Mapped to {@code -Dsignpath.signProjectArtifact}. Default is
	 * {@code true}.
	 */
	@Parameter(property = "signpath.signProjectArtifact", defaultValue = "true")
	private boolean signProjectArtifact;

	/**
	 * Whether to include Maven attached artifacts in signing.
	 * <p>
	 * Optional. Mapped to {@code -Dsignpath.signAttachedArtifacts}. Default is
	 * {@code true}.
	 */
	@Parameter(property = "signpath.signAttachedArtifacts", defaultValue = "true")
	private boolean signAttachedArtifacts;

	/**
	 * Polling interval in seconds when checking signing request status.
	 * <p>
	 * Optional. Mapped to {@code -Dsignpath.pollInterval}. Default is 5.
	 */
	@Parameter(property = "signpath.pollInterval", defaultValue = "5")
	private int pollInterval;

	/**
	 * Delay in seconds between HTTP retry attempts.
	 * <p>
	 * Optional. Mapped to {@code -Dsignpath.retryInterval}. Default is 30.
	 */
	@Parameter(property = "signpath.retryInterval", defaultValue = "30")
	private int retryInterval;

	/**
	 * Maximum retry time window in seconds for transient HTTP failures.
	 * <p>
	 * Optional. Mapped to {@code -Dsignpath.retryTimeout}. Default is 600.
	 */
	@Parameter(property = "signpath.retryTimeout", defaultValue = "600")
	private int retryTimeout;

	/**
	 * Maximum number of retry attempts for transient HTTP failures.
	 * <p>
	 * Optional. Mapped to {@code -Dsignpath.maxRetries}. Default is 10.
	 */
	@Parameter(property = "signpath.maxRetries", defaultValue = "10")
	private int maxRetries;

	/**
	 * Read/write HTTP timeout in seconds for SignPath API calls.
	 * <p>
	 * Optional. Mapped to {@code -Dsignpath.httpTimeout}. Default is 300.
	 */
	@Parameter(property = "signpath.httpTimeout", defaultValue = "300")
	private int httpTimeout;

	/**
	 * HTTP connection timeout in seconds for SignPath API calls.
	 * <p>
	 * Optional. Mapped to {@code -Dsignpath.connectTimeout}. Default is 30.
	 */
	@Parameter(property = "signpath.connectTimeout", defaultValue = "30")
	private int connectTimeout;

	/**
	 * Optional custom key/value parameters forwarded to SignPath in the submit
	 * request.
	 */
	@Parameter
	private Map<String, String> parameters;

	/**
	 * Fails the build when no files are found to sign.
	 * <p>
	 * Optional. Mapped to {@code -Dsignpath.failOnNoFilesFound}. Default is
	 * {@code false}.
	 */
	@Parameter(property = "signpath.failOnNoFilesFound", defaultValue = "false")
	private boolean failOnNoFilesFound;

	/**
	 * Skips plugin execution.
	 * <p>
	 * Optional. Mapped to {@code -Dsignpath.skip}. Default is {@code false}.
	 */
	@Parameter(property = "signpath.skip", defaultValue = "false")
	private boolean skip;

	/**
	 * Alias for skipping plugin execution.
	 * <p>
	 * Optional. Mapped to {@code -Dsignpath.skipSigning}. Default is
	 * {@code false}.
	 */
	@Parameter(property = "signpath.skipSigning", defaultValue = "false")
	private boolean skipSigning;

	/**
	 * Creates the mojo with an injected Maven settings decrypter.
	 *
	 * @param settingsDecrypter component used to decrypt server credentials from
	 *                          {@code settings.xml}
	 */
	@Inject
	public SignMojo(SettingsDecrypter settingsDecrypter) {
		this.settingsDecrypter = settingsDecrypter;
	}

	/**
	 * Executes the signing goal: collects artifacts, submits them to the SignPath API,
	 * polls until completion, and writes the signed artifacts to the output location.
	 *
	 * @throws MojoExecutionException on configuration or API errors
	 * @throws MojoFailureException   when signing completes with a non-success status
	 */
	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (skip || skipSigning || isSkipSigningFromEnvironment()) {
			getLog().info("Signing is skipped");
			return;
		}

		List<Path> filesToSign = collectFilesToSign();
		if (filesToSign.isEmpty()) {
			if (failOnNoFilesFound) {
				throw new MojoFailureException("No files found to sign");
			}
			getLog().warn("No files selected for signing");
			return;
		}

		String resolvedApiToken = resolveApiToken();

		SignPathClient.Config config = new SignPathClient.Config(
				baseUrl, organizationId, resolvedApiToken,
				Duration.ofSeconds(connectTimeout),
				Duration.ofSeconds(httpTimeout),
				Duration.ofSeconds(retryTimeout),
				Duration.ofSeconds(retryInterval),
				maxRetries);

		try (SignPathClient client = new SignPathClient(config)) {
			for (Path filePath : filesToSign) {
				signFile(client, filePath);
			}
		}
	}

	private List<Path> collectFilesToSign() throws MojoExecutionException {
		Set<Path> files = new LinkedHashSet<>();

		Path basePath = Path.of(baseDirectory);
		for (String relativePath : scanFiles()) {
			files.add(basePath.resolve(relativePath).toAbsolutePath().normalize());
		}

		if (signProjectArtifact && project != null && project.getArtifact() != null) {
			addArtifactFile(files, project.getArtifact().getFile(), "project artifact");
		}

		if (signAttachedArtifacts && project != null && project.getAttachedArtifacts() != null) {
			for (var artifact : project.getAttachedArtifacts()) {
				addArtifactFile(files, artifact.getFile(), "attached artifact");
			}
		}

		return new ArrayList<>(files);
	}

	private void addArtifactFile(Set<Path> files, File artifactFile, String artifactType) {
		if (artifactFile == null) {
			return;
		}

		Path artifactPath = artifactFile.toPath().toAbsolutePath().normalize();
		if (!Files.isRegularFile(artifactPath)) {
			getLog().warn("Skipping " + artifactType + " because file does not exist: " + artifactPath);
			return;
		}

		files.add(artifactPath);
	}

	/**
	 * Checks whether signing should be skipped based on the
	 * {@value #SIGNPATH_SKIP_SIGNING} environment variable.
	 * <p>
	 * If set to {@code 1}, {@code true}, or {@code yes} (case-insensitive),
	 * execution is skipped.
	 *
	 * @return {@code true} when environment-based skip is enabled, otherwise
	 *         {@code false}
	 */
	private boolean isSkipSigningFromEnvironment() {
		String envValue = getEnvironmentVariable(SIGNPATH_SKIP_SIGNING);
		if (envValue == null || envValue.isBlank()) {
			return false;
		}

		String normalized = envValue.trim();
		boolean skipRequested = "1".equals(normalized)
				|| "true".equalsIgnoreCase(normalized)
				|| "yes".equalsIgnoreCase(normalized);

		if (skipRequested) {
			getLog().debug("Skipping signing because " + SIGNPATH_SKIP_SIGNING + " is set");
		}

		return skipRequested;
	}

	/**
	 * Resolves the SignPath API token in the following order:
	 * <ol>
	 * <li>{@code signpath.apiToken} parameter / system property</li>
	 * <li>Maven {@code settings.xml} server password for {@code serverId}</li>
	 * <li>{@value #SIGNPATH_API_TOKEN} environment variable</li>
	 * </ol>
	 *
	 * @return resolved API token
	 * @throws MojoExecutionException if no token source is configured
	 */
	String resolveApiToken() throws MojoExecutionException {
		if (apiToken != null && !apiToken.isBlank()) {
			getLog().debug("Using API token from parameter/system property");
			return apiToken;
		}

		if (session != null) {
			Server server = session.getSettings().getServer(serverId);
			if (server != null) {
				String password = decryptServerPassword(server);
				if (password != null && !password.isBlank()) {
					getLog().debug("Using API token from settings.xml server '" + serverId + "'");
					return password;
				}
			}
		}

		String envToken = getEnvironmentVariable(SIGNPATH_API_TOKEN);
		if (envToken != null && !envToken.isBlank()) {
			getLog().debug("Using API token from " + SIGNPATH_API_TOKEN + " environment variable");
			return envToken;
		}

		throw new MojoExecutionException(
				"No API token found. Provide it via one of the following (in priority order):\n"
						+ "  1. <apiToken> parameter or -Dsignpath.apiToken system property\n"
						+ "  2. Server password in settings.xml with server ID '" + serverId + "'\n"
						+ "  3. " + SIGNPATH_API_TOKEN + " environment variable");
	}

	/**
	 * Returns the decrypted password for the given server definition.
	 *
	 * @param server Maven server definition
	 * @return decrypted password, or plain password when no decrypter is available
	 */
	private String decryptServerPassword(Server server) {
		if (settingsDecrypter == null) {
			return server.getPassword();
		}
		SettingsDecryptionResult result = settingsDecrypter.decrypt(
				new DefaultSettingsDecryptionRequest(server));
		for (var problem : result.getProblems()) {
			getLog().warn("Settings decryption problem: " + problem);
		}
		return result.getServer().getPassword();
	}

	/**
	 * Looks up an environment variable.
	 * <p>
	 * Kept non-private to support tests overriding token lookup behavior.
	 *
	 * @param name environment variable name
	 * @return environment variable value, or {@code null} if unset
	 */
	String getEnvironmentVariable(String name) {
		return System.getenv(name);
	}

	/**
	 * Submits a single artifact for signing, waits for completion, and writes the
	 * signed artifact to the output location.
	 *
	 * @param client   SignPath client
	 * @param filePath artifact path to sign
	 * @throws MojoExecutionException on API or I/O failures
	 * @throws MojoFailureException   when signing completes with a non-success status
	 */
	private void signFile(SignPathClient client, Path filePath)
			throws MojoExecutionException, MojoFailureException {
		getLog().info("Submitting for signing: " + filePath);

		try {
			SigningRequest signingRequest = client.submit(
					projectSlug, signingPolicySlug, artifactConfigurationSlug,
					description, parameters, filePath);
			getLog().info("Signing request submitted, polling status at: " + signingRequest.statusUrl());

			SigningRequestStatus status = pollUntilFinal(client, signingRequest);

			if (!status.isCompleted()) {
				throw new MojoFailureException(
						"Signing request did not complete successfully. Status: " + status.status()
								+ ", workflow status: " + status.workflowStatus());
			}

			Path outputPath = resolveOutputPath(filePath);
			Path tmpPath = outputPath.resolveSibling(outputPath.getFileName() + ".signing-tmp");
			try {
				getLog().info("Downloading signed artifact to: " + outputPath);
				client.downloadSignedArtifact(status, tmpPath);
				Files.move(tmpPath, outputPath, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} finally {
				Files.deleteIfExists(tmpPath);
			}

			getLog().info("Successfully signed: " + outputPath);
		} catch (SignPathException e) {
			throw new MojoExecutionException("SignPath API error while signing " + filePath, e);
		} catch (IOException e) {
			throw new MojoExecutionException("I/O error while signing " + filePath, e);
		}
	}

	/**
	 * Polls SignPath until the request reaches a final state.
	 *
	 * @param client         SignPath client
	 * @param signingRequest request to poll
	 * @return final request status
	 * @throws SignPathException on API-level errors
	 * @throws IOException       when polling is interrupted or transport fails
	 */
	private SigningRequestStatus pollUntilFinal(SignPathClient client, SigningRequest signingRequest)
			throws SignPathException, IOException {
		while (true) {
			SigningRequestStatus status = client.getStatus(signingRequest);
			getLog().info("Signing status: " + status.status()
					+ " (workflow: " + status.workflowStatus() + ")");

			if (status.isFinalStatus()) {
				return status;
			}

			try {
				Thread.sleep(Duration.ofSeconds(pollInterval).toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Polling interrupted", e);
			}
		}
	}

	/**
	 * Resolves the output location for a signed artifact.
	 *
	 * @param inputPath original artifact path
	 * @return target output path
	 */
	private Path resolveOutputPath(Path inputPath) {
		if (outputDirectory != null) {
			return Path.of(outputDirectory).resolve(inputPath.getFileName());
		}
		return inputPath;
	}

	/**
	 * Scans the base directory and returns relative paths of files matching include
	 * and exclude glob filters.
	 *
	 * @return matching relative file paths
	 * @throws MojoExecutionException if file system scanning fails
	 */
	private String[] scanFiles() throws MojoExecutionException {
		Path basePath = Path.of(baseDirectory);
		if (!Files.isDirectory(basePath)) {
			return new String[0];
		}

		FileSystem fs = basePath.getFileSystem();
		List<PathMatcher> includeMatchers = toPathMatchers(fs, includes);
		List<PathMatcher> excludeMatchers = toPathMatchers(fs, excludes);

		if (includeMatchers.isEmpty()) {
			if (!excludeMatchers.isEmpty()) {
				getLog().warn("<excludes> is configured but has no effect because <includes> is empty");
			}
			return new String[0];
		}

		try (Stream<Path> stream = Files.walk(basePath)) {
			return stream
					.filter(Files::isRegularFile)
					.map(basePath::relativize)
					.filter(p -> includeMatchers.stream().anyMatch(m -> m.matches(p)))
					.filter(p -> excludeMatchers.stream().noneMatch(m -> m.matches(p)))
					.map(Path::toString)
					.toArray(String[]::new);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to scan files in " + baseDirectory, e);
		}
	}

	/**
	 * Converts glob pattern strings to file-system-specific path matchers.
	 *
	 * @param fs       file system used to create matchers
	 * @param patterns glob patterns (without {@code glob:} prefix)
	 * @return list of path matchers, possibly empty
	 */
	private static List<PathMatcher> toPathMatchers(FileSystem fs, String[] patterns) {
		List<PathMatcher> matchers = new ArrayList<>();
		if (patterns != null) {
			for (String pattern : patterns) {
				matchers.add(fs.getPathMatcher("glob:" + pattern));
			}
		}
		return matchers;
	}
}
