package de.opeldus.datacontract.publish;

import static de.opeldus.datacontract.common.ApiExceptions.badGateway;

import de.opeldus.datacontract.artifact.GeneratedArtifact;
import de.opeldus.datacontract.security.CurrentUser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Commits with the person as author and the bot as committer. If no remote is configured, commits stay in the
 * local work directory.
 */
@Component
public class GitPublisher {

    private static final Logger log = LoggerFactory.getLogger(GitPublisher.class);

    private final GitProperties properties;

    public GitPublisher(GitProperties properties) {
        this.properties = properties;
    }

    public synchronized String publish(List<GeneratedArtifact> artifacts, String message,
            CurrentUser.Identity author) {
        try (var git = openWorkspace()) {
            syncWithRemote(git);
            var root = git.getRepository().getWorkTree().toPath().toAbsolutePath().normalize();
            for (var artifact : artifacts) {
                var target = root.resolve(artifact.path()).normalize();
                if (!target.startsWith(root)) {
                    throw new IllegalArgumentException("Artifact path escapes the repository: " + artifact.path());
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, artifact.content(), StandardCharsets.UTF_8);
                git.add().addFilepattern(root.relativize(target).toString().replace(File.separatorChar, '/')).call();
            }
            var commit = git.commit()
                    .setMessage(message)
                    .setAuthor(new PersonIdent(author.displayName(), author.email()))
                    .setCommitter(new PersonIdent(properties.botName(), properties.botEmail()))
                    .setSign(false)
                    .setAllowEmpty(true)
                    .call();
            push(git);
            return commit.getName();
        } catch (GitAPIException | IOException | RuntimeException exception) {
            log.error("Publishing to Git failed", exception);
            throw badGateway("Die Veröffentlichung nach Git ist fehlgeschlagen. Das Mapping bleibt freigegeben.");
        }
    }

    private Git openWorkspace() throws IOException, GitAPIException {
        var workdir = Path.of(properties.workdir()).toFile();
        Git git;
        if (new File(workdir, ".git").exists()) {
            git = Git.open(workdir);
        } else {
            Files.createDirectories(workdir.toPath());
            git = Git.init().setDirectory(workdir).setInitialBranch(properties.branch()).call();
        }
        if (hasRemote()) {
            var config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", resolvedRemoteUrl());
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            config.save();
        }
        return git;
    }

    /** Git would resolve a relative path against the work directory instead of the application's directory. */
    private String resolvedRemoteUrl() {
        var url = properties.remoteUrl();
        return url.startsWith(".") ? Path.of(url).toAbsolutePath().normalize().toString() : url;
    }

    /** Local state is disposable: the remote branch always wins, so a failed publish leaves nothing behind. */
    private void syncWithRemote(Git git) throws GitAPIException, IOException {
        if (!hasRemote()) {
            return;
        }
        var fetch = git.fetch().setRemote("origin");
        if (StringUtils.hasText(properties.token())) {
            fetch.setCredentialsProvider(credentials());
        }
        fetch.call();
        var remoteBranch = Constants.R_REMOTES + "origin/" + properties.branch();
        Ref remote = git.getRepository().findRef(remoteBranch);
        if (remote != null) {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteBranch).call();
        } else if (git.getRepository().resolve(Constants.HEAD) != null) {
            throw new IllegalStateException("Remote branch is missing but local commits exist: "
                    + properties.branch());
        }
        git.clean().setCleanDirectories(true).setForce(true).call();
    }

    private void push(Git git) throws GitAPIException, IOException {
        if (!hasRemote()) {
            return;
        }
        var push = git.push()
                .setRemote("origin")
                .setRefSpecs(new RefSpec("HEAD:" + Constants.R_HEADS + properties.branch()));
        if (StringUtils.hasText(properties.token())) {
            push.setCredentialsProvider(credentials());
        }
        for (var result : push.call()) {
            for (var update : result.getRemoteUpdates()) {
                if (update.getStatus() != RemoteRefUpdate.Status.OK
                        && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                    rollbackLocalCommit(git);
                    throw new IllegalStateException("Push rejected: " + update.getStatus());
                }
            }
        }
    }

    private void rollbackLocalCommit(Git git) throws GitAPIException, IOException {
        var remoteBranch = Constants.R_REMOTES + "origin/" + properties.branch();
        if (git.getRepository().findRef(remoteBranch) != null) {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteBranch).call();
        }
    }

    private boolean hasRemote() {
        return StringUtils.hasText(properties.remoteUrl());
    }

    private UsernamePasswordCredentialsProvider credentials() {
        var username = StringUtils.hasText(properties.username()) ? properties.username() : "x-access-token";
        return new UsernamePasswordCredentialsProvider(username, properties.token());
    }
}
