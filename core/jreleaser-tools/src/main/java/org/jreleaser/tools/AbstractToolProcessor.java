/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2020-2021 Andres Almiray.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jreleaser.tools;

import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jreleaser.model.Artifact;
import org.jreleaser.model.Distribution;
import org.jreleaser.model.GitService;
import org.jreleaser.model.JReleaserContext;
import org.jreleaser.model.Project;
import org.jreleaser.model.Release;
import org.jreleaser.model.Tool;
import org.jreleaser.model.releaser.spi.Releaser;
import org.jreleaser.model.releaser.spi.Repository;
import org.jreleaser.model.tool.spi.ToolProcessingException;
import org.jreleaser.model.tool.spi.ToolProcessor;
import org.jreleaser.releaser.Releasers;
import org.jreleaser.util.Constants;
import org.jreleaser.util.FileUtils;
import org.zeroturnaround.exec.ProcessExecutor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.jreleaser.templates.TemplateUtils.resolveAndMergeTemplates;
import static org.jreleaser.util.FileUtils.createDirectoriesWithFullAccess;
import static org.jreleaser.util.FileUtils.grantFullAccess;
import static org.jreleaser.util.MustacheUtils.applyTemplate;
import static org.jreleaser.util.StringUtils.capitalize;
import static org.jreleaser.util.StringUtils.isBlank;
import static org.jreleaser.util.StringUtils.isNotBlank;

/**
 * @author Andres Almiray
 * @since 0.1.0
 */
abstract class AbstractToolProcessor<T extends Tool> implements ToolProcessor<T> {
    protected final JReleaserContext context;
    protected final T tool;

    protected AbstractToolProcessor(JReleaserContext context, T tool) {
        this.context = context;
        this.tool = tool;
    }

    @Override
    public T getTool() {
        return tool;
    }

    @Override
    public String getToolName() {
        return tool.getName();
    }

    @Override
    public boolean prepareDistribution(Distribution distribution, Map<String, Object> props) throws ToolProcessingException {
        try {
            String distributionName = distribution.getName();
            context.getLogger().debug("Creating props for {}/{}", distributionName, getToolName());
            Map<String, Object> newProps = fillProps(distribution, props);
            if (newProps.isEmpty()) {
                context.getLogger().warn("Skipping {} for {} distribution", getToolName(), distributionName);
                return false;
            }
            context.getLogger().debug("Resolving templates for {}/{}", distributionName, getToolName());
            Map<String, Reader> templates = resolveAndMergeTemplates(context.getLogger(), distribution.getType(), getToolName(),
                context.getBasedir().resolve(getTool().getTemplateDirectory()));
            for (Map.Entry<String, Reader> entry : templates.entrySet()) {
                context.getLogger().debug("Evaluating template {} for {}/{}", entry.getKey(), distributionName, getToolName());
                String content = applyTemplate(entry.getValue(), newProps);
                context.getLogger().debug("Writing template {} for {}/{}", entry.getKey(), distributionName, getToolName());
                writeFile(context.getModel().getProject(), distribution, content, props, entry.getKey());
            }
        } catch (IllegalArgumentException e) {
            throw new ToolProcessingException(e);
        }

        return true;
    }

    @Override
    public boolean packageDistribution(Distribution distribution, Map<String, Object> props) throws ToolProcessingException {
        try {
            String distributionName = distribution.getName();
            context.getLogger().debug("Creating props for {}/{}", distributionName, getToolName());
            Map<String, Object> newProps = fillProps(distribution, props);
            if (newProps.isEmpty()) {
                context.getLogger().warn("Skipping {} for {} distribution", getToolName(), distributionName);
                return false;
            }
            return doPackageDistribution(distribution, newProps);
        } catch (IllegalArgumentException e) {
            throw new ToolProcessingException(e);
        }
    }

    @Override
    public boolean uploadDistribution(Distribution distribution, Map<String, Object> props) throws ToolProcessingException {
        try {
            String distributionName = distribution.getName();
            context.getLogger().debug("Creating props for {}/{}", distributionName, getToolName());
            Map<String, Object> newProps = fillProps(distribution, props);
            if (newProps.isEmpty()) {
                context.getLogger().warn("Skipping {} for {} distribution", getToolName(), distributionName);
                return false;
            }
            return doUploadDistribution(distribution, newProps);
        } catch (IllegalArgumentException e) {
            throw new ToolProcessingException(e);
        }
    }

    protected abstract boolean doPackageDistribution(Distribution distribution, Map<String, Object> props) throws ToolProcessingException;

    protected boolean doUploadDistribution(Distribution distribution, Map<String, Object> props) throws ToolProcessingException {
        return doUploadDistribution(distribution, props, getUploadRepositoryName(distribution));
    }

    protected boolean doUploadDistribution(Distribution distribution, Map<String, Object> props, String repositoryName) throws ToolProcessingException {
        Releaser releaser = Releasers.releaser(context);
        GitService gitService = context.getModel().getRelease().getGitService();

        try {
            // get the repository
            context.getLogger().debug("Locating repository {}/{}", gitService.getOwner(), repositoryName);
            Repository repository = releaser.maybeCreateRepository(repositoryName);

            // clone the repository
            context.getLogger().debug("Clonning {}", repository.getHttpUrl());
            Path directory = Files.createTempDirectory("jreleaser-" + repositoryName);
            Git git = Git.cloneRepository()
                .setBranch("HEAD")
                .setDirectory(directory.toFile())
                .setURI(repository.getHttpUrl())
                .call();

            // copy files over
            Path packageDirectory = (Path) props.get(Constants.KEY_PACKAGE_DIRECTORY);
            context.getLogger().debug("Copying files from {}", context.getBasedir().relativize(packageDirectory));
            FileUtils.copyFiles(context.getLogger(), packageDirectory, directory);

            UsernamePasswordCredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(
                gitService.getUsername(), gitService.getResolvedPassword());

            // add everything
            git.add()
                .addFilepattern(".")
                .call();

            // setup commit
            context.getLogger().debug("Setting up commit");
            CommitCommand commitCommand = git.commit()
                .setAll(true)
                .setMessage(distribution.getExecutable() + " " + gitService.getTagName())
                .setAuthor(gitService.getCommitAuthorName(), gitService.getCommitAuthorEmail());
            commitCommand.setCredentialsProvider(credentialsProvider);
            /*if (gitService.isSign()) {
                commitCommand = commitCommand
                    .setSign(true)
                    .setSigningKey(gitService.getSigningKey());
            }*/
            commitCommand.call();

            // push commit
            context.getLogger().debug("Pushing commit to remote, dryrun = {}", context.isDryrun());
            git.push()
                .setDryRun(context.isDryrun())
                .setPushAll()
                .setCredentialsProvider(credentialsProvider)
                .call();
        } catch (Exception e) {
            throw new ToolProcessingException("Unexpected error updating " + repositoryName, e);
        }

        return true;
    }

    protected abstract String getUploadRepositoryName(Distribution distribution);

    protected abstract void writeFile(Project project, Distribution distribution, String content, Map<String, Object> props, String fileName) throws ToolProcessingException;

    protected void writeFile(String content, Path outputFile) throws ToolProcessingException {
        try {
            createDirectoriesWithFullAccess(outputFile.getParent());
            Files.write(outputFile, content.getBytes(), CREATE, WRITE, TRUNCATE_EXISTING);
            grantFullAccess(outputFile);
        } catch (IOException e) {
            throw new ToolProcessingException("Unexpected error when writing to " + outputFile.toAbsolutePath(), e);
        }
    }

    protected Map<String, Object> fillProps(Distribution distribution, Map<String, Object> props) throws ToolProcessingException {
        Map<String, Object> newProps = context.getModel().props();
        newProps.putAll(props);
        context.getLogger().debug("Filling distribution properties into props");
        fillDistributionProperties(newProps, distribution, context.getModel().getRelease());
        context.getLogger().debug("Filling git properties into props");
        context.getModel().getRelease().getGitService().fillProps(newProps, context.getModel().getProject());
        context.getLogger().debug("Filling artifact properties into props");
        if (!verifyAndAddArtifacts(newProps, distribution)) {
            // we can't continue with this tool
            return Collections.emptyMap();
        }
        context.getLogger().debug("Filling tool properties into props");
        fillToolProperties(newProps, distribution);
        newProps.putAll(tool.getResolvedExtraProperties());
        return newProps;
    }

    protected void fillDistributionProperties(Map<String, Object> props, Distribution distribution, Release release) {
        props.put(Constants.KEY_DISTRIBUTION_NAME, distribution.getName());
        props.put(Constants.KEY_DISTRIBUTION_EXECUTABLE, distribution.getExecutable());
        props.put(Constants.KEY_DISTRIBUTION_TAGS_BY_SPACE, String.join(" ", distribution.getTags()));
        props.put(Constants.KEY_DISTRIBUTION_TAGS_BY_COMMA, String.join(",", distribution.getTags()));
        props.put(Constants.KEY_DISTRIBUTION_RELEASE_NOTES, applyTemplate(new StringReader(release.getGitService().getReleaseNotesUrlFormat()), props));
        props.put(Constants.KEY_DISTRIBUTION_ISSUE_TRACKER, applyTemplate(new StringReader(release.getGitService().getIssueTrackerUrlFormat()), props));
        props.put(Constants.KEY_DISTRIBUTION_LATEST_RELEASE, applyTemplate(new StringReader(release.getGitService().getLatestReleaseUrlFormat()), props));
        props.putAll(distribution.getResolvedExtraProperties());
    }

    protected abstract void fillToolProperties(Map<String, Object> context, Distribution distribution) throws ToolProcessingException;

    protected boolean executeCommand(List<String> cmd) throws ToolProcessingException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            int exitValue = new ProcessExecutor(cmd)
                .redirectOutput(out)
                .redirectError(err)
                .execute()
                .getExitValue();

            info(out);
            error(err);

            if (exitValue == 0) return true;
            throw new ToolProcessingException("Command execution error. exitValue = " + exitValue);
        } catch (Exception e) {
            if (e instanceof ToolProcessingException) {
                throw (ToolProcessingException) e;
            }
            throw new ToolProcessingException("Unexpected error", e);
        }
    }

    protected void copyPreparedFiles(Distribution distribution, Map<String, Object> props) throws ToolProcessingException {
        Path prepareDirectory = (Path) props.get(Constants.KEY_PREPARE_DIRECTORY);
        Path packageDirectory = (Path) props.get(Constants.KEY_PACKAGE_DIRECTORY);
        try {
            FileUtils.copyFiles(context.getLogger(), prepareDirectory, packageDirectory);
        } catch (IOException e) {
            throw new ToolProcessingException("Unexpected error when copying files from " +
                context.getBasedir().relativize(prepareDirectory) + " to " +
                context.getBasedir().relativize(packageDirectory), e);
        }
    }

    private boolean verifyAndAddArtifacts(Map<String, Object> props,
                                          Distribution distribution) throws ToolProcessingException {
        Set<String> fileExtensions = resolveByExtensionsFor(distribution.getType());
        List<Artifact> artifacts = distribution.getArtifacts().stream()
            .filter(artifact -> fileExtensions.stream().anyMatch(ext -> artifact.getPath().endsWith(ext)))
            .collect(Collectors.toList());

        if (artifacts.size() == 0) {
            // we can't proceed
            context.getLogger().warn("No suitable artifacts found in distribution {} to be packaged with {}",
                distribution.getName(), capitalize(tool.getName()));
            return false;
        }

        for (int i = 0; i < artifacts.size(); i++) {
            Artifact artifact = artifacts.get(i);
            String classifier = isNotBlank(artifact.getPlatform()) ? capitalize(artifact.getPlatform()) : "";
            String artifactFileName = Paths.get(artifact.getPath()).getFileName().toString();
            props.put("artifact" + classifier + "JavaVersion", artifact.getJavaVersion());
            props.put("artifact" + classifier + "FileName", artifactFileName);
            props.put("artifact" + classifier + "Hash", artifact.getHash());
            Map<String, Object> newProps = new LinkedHashMap<>(props);
            newProps.put("artifactFileName", artifactFileName);
            String artifactUrl = applyTemplate(new StringReader(context.getModel().getRelease().getGitService().getDownloadUrlFormat()), newProps, "downloadUrl");
            props.put("artifact" + classifier + "Url", artifactUrl);

            if (0 == i) {
                props.put(Constants.KEY_DISTRIBUTION_URL, artifactUrl);
                props.put(Constants.KEY_DISTRIBUTION_SHA_256, artifact.getHash());
                props.put(Constants.KEY_DISTRIBUTION_JAVA_VERSION, artifact.getJavaVersion());
                props.put(Constants.KEY_DISTRIBUTION_FILE_NAME, artifactFileName);
            }
        }

        return true;
    }

    protected abstract Set<String> resolveByExtensionsFor(Distribution.DistributionType type);

    protected void info(ByteArrayOutputStream out) {
        log(out, context.getLogger()::info);
    }

    protected void error(ByteArrayOutputStream err) {
        log(err, context.getLogger()::error);
    }

    private void log(ByteArrayOutputStream stream, Consumer<? super String> consumer) {
        String str = stream.toString();
        if (isBlank(str)) return;

        Arrays.stream(str.split(System.lineSeparator()))
            .forEach(consumer);
    }
}
