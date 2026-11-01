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
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Commits with the person as author and the bot as committer. If no remote is configured, commits stay in the local
 * work directory. Files are always generated from the data, so a branch receives their new content directly instead of
 * going through a Git merge, and users never see merge conflicts in Git.
 */
@Component
public class GitPublisher {

    private static final Logger log = LoggerFactory.getLogger(GitPublisher.class);

    private final GitProperties properties;

    public GitPublisher(GitProperties properties) {
        this.properties = properties;
    }

    public String publish(List<GeneratedArtifact> artifacts, String message, CurrentUser.Identity author) {
        return publish(properties.branch(), artifacts, message, author);
    }

    /** Returns the commit; if the branch already has exactly this content, that is the current one. */
    public synchronized String publish(String branch, List<GeneratedArtifact> artifacts, String message,
            CurrentUser.Identity author) {
        try (var git = openWorkspace()) {
            fetch(git);
            checkout(git, branch);
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
            if (git.status().call().isClean() && head(git.getRepository()) != null) {
                return head(git.getRepository());
            }
            var commit = git.commit()
                    .setMessage(message)
                    .setAuthor(new PersonIdent(author.displayName(), author.email()))
                    .setCommitter(new PersonIdent(properties.botName(), properties.botEmail()))
                    .setSign(false)
                    .call();
            push(git, branch);
            return commit.getName();
        } catch (GitAPIException | IOException | RuntimeException exception) {
            log.error("Publishing to Git failed", exception);
            throw badGateway("Die Veröffentlichung nach Git ist fehlgeschlagen. Der Stand bleibt freigegeben.");
        }
    }

    /** Creates the branch from the current state of its base; an existing branch stays as it is. */
    public synchronized void createBranch(String branch, String base) {
        try (var git = openWorkspace()) {
            fetch(git);
            ensureBaseExists(git, base);
            if (hasRemote()) {
                if (git.getRepository().findRef(remoteRef(branch)) == null) {
                    var push = git.push().setRemote("origin").setRefSpecs(new RefSpec(remoteRef(base) + ":" + Constants.R_HEADS + branch));
                    verify(authenticated(push).call());
                    fetch(git);
                }
            } else if (git.getRepository().findRef(Constants.R_HEADS + branch) == null) {
                git.branchCreate().setName(branch).setStartPoint(Constants.R_HEADS + base).call();
            }
        } catch (GitAPIException | IOException | RuntimeException exception) {
            log.error("Creating a Git branch failed", exception);
            throw badGateway("Der Branch konnte nicht angelegt werden.");
        }
    }

    public synchronized void tag(String name, String commit, String message) {
        try (var git = openWorkspace()) {
            fetch(git);
            var object = git.getRepository().parseCommit(git.getRepository().resolve(commit));
            git.tag().setName(name).setObjectId(object).setMessage(message).setSigned(false).setForceUpdate(true)
                    .setTagger(new PersonIdent(properties.botName(), properties.botEmail())).call();
            if (hasRemote()) {
                var tagRef = Constants.R_TAGS + name;
                verify(authenticated(git.push().setRemote("origin").setRefSpecs(new RefSpec(tagRef + ":" + tagRef))).call());
            }
        } catch (GitAPIException | IOException | RuntimeException exception) {
            log.error("Creating a Git tag failed", exception);
            throw badGateway("Der Tag konnte nicht angelegt werden.");
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

    private void fetch(Git git) throws GitAPIException {
        if (hasRemote()) {
            authenticated(git.fetch().setRemote("origin")).call();
        }
    }

    /** Local state is disposable: the remote branch always wins, so a failed publish leaves nothing behind. */
    private void checkout(Git git, String branch) throws GitAPIException, IOException {
        var repository = git.getRepository();
        var localRef = Constants.R_HEADS + branch;
        if (hasRemote()) {
            var remote = repository.findRef(remoteRef(branch));
            if (remote == null) {
                if (!branch.equals(properties.branch())) {
                    throw new IllegalStateException("Remote branch is missing: " + branch);
                }
                if (head(repository) != null) {
                    throw new IllegalStateException("Remote branch is missing but local commits exist: " + branch);
                }
                return;
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
            git.checkout().setName(branch).setCreateBranch(repository.findRef(localRef) == null)
                    .setStartPoint(remote.getName()).setForced(true).call();
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remote.getName()).call();
        } else if (repository.findRef(localRef) != null) {
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
            git.checkout().setName(branch).setForced(true).call();
        } else if (head(repository) != null) {
            throw new IllegalStateException("Local branch is missing: " + branch);
        }
        git.clean().setCleanDirectories(true).setForce(true).call();
    }

    /** A branch needs something to start from, also while the repository is still empty. */
    private void ensureBaseExists(Git git, String base) throws GitAPIException, IOException {
        var repository = git.getRepository();
        var exists = hasRemote() ? repository.findRef(remoteRef(base)) != null : repository.findRef(Constants.R_HEADS + base) != null;
        if (exists) {
            return;
        }
        if (!base.equals(properties.branch()) || head(repository) != null) {
            throw new IllegalStateException("Base branch is missing: " + base);
        }
        var bot = new PersonIdent(properties.botName(), properties.botEmail());
        git.commit().setMessage("Initialize repository").setAuthor(bot).setCommitter(bot).setAllowEmpty(true).setSign(false).call();
        push(git, base);
        fetch(git);
    }

    private void push(Git git, String branch) throws GitAPIException, IOException {
        if (!hasRemote()) {
            return;
        }
        var push = git.push().setRemote("origin").setRefSpecs(new RefSpec("HEAD:" + Constants.R_HEADS + branch));
        try {
            verify(authenticated(push).call());
        } catch (IllegalStateException rejected) {
            var remote = git.getRepository().findRef(remoteRef(branch));
            if (remote != null) {
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remote.getName()).call();
            }
            throw rejected;
        }
    }

    private static void verify(Iterable<org.eclipse.jgit.transport.PushResult> results) {
        for (var result : results) {
            for (var update : result.getRemoteUpdates()) {
                if (update.getStatus() != RemoteRefUpdate.Status.OK
                        && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                    throw new IllegalStateException("Push rejected: " + update.getStatus());
                }
            }
        }
    }

    private <C extends TransportCommand<C, ?>> C authenticated(C command) {
        if (StringUtils.hasText(properties.token())) {
            var username = StringUtils.hasText(properties.username()) ? properties.username() : "x-access-token";
            command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, properties.token()));
        }
        return command;
    }

    private static String remoteRef(String branch) {
        return Constants.R_REMOTES + "origin/" + branch;
    }

    private static String head(Repository repository) throws IOException {
        var head = repository.resolve(Constants.HEAD);
        return head == null ? null : head.getName();
    }

    private boolean hasRemote() {
        return StringUtils.hasText(properties.remoteUrl());
    }
}
