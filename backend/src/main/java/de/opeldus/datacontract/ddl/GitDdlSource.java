package de.opeldus.datacontract.ddl;

import de.opeldus.datacontract.ddl.DdlSchema.DdlFile;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Reads every .sql file of a branch; the local copy is disposable and always follows the remote. */
@Component
@ConditionalOnExpression("!'${app.ddl.remote-url:}'.isBlank()")
public class GitDdlSource implements DdlSource {

    private static final Logger log = LoggerFactory.getLogger(GitDdlSource.class);
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final DdlProperties properties;

    public GitDdlSource(DdlProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized Snapshot load() {
        try (var git = open()) {
            var remoteBranch = Constants.R_REMOTES + "origin/" + properties.branch();
            authenticated(git.fetch().setRemote("origin")).call();
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteBranch).call();
            git.clean().setCleanDirectories(true).setForce(true).call();
            var revision = git.getRepository().resolve(remoteBranch).getName();
            return new Snapshot(revision, readFiles(git.getRepository().getWorkTree().toPath()));
        } catch (GitAPIException | IOException exception) {
            throw new IllegalStateException("DDL repository could not be read: " + exception.getMessage(), exception);
        }
    }

    private Git open() throws IOException, GitAPIException {
        var workdir = Path.of(properties.workdir()).toFile();
        Git git;
        if (new File(workdir, ".git").exists()) {
            git = Git.open(workdir);
        } else {
            Files.createDirectories(workdir.toPath());
            git = Git.init().setDirectory(workdir).setInitialBranch(properties.branch()).call();
        }
        var url = properties.remoteUrl();
        var config = git.getRepository().getConfig();
        config.setString("remote", "origin", "url", url.startsWith(".") ? Path.of(url).toAbsolutePath().normalize().toString() : url);
        config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
        config.save();
        return git;
    }

    private List<DdlFile> readFiles(Path root) throws IOException {
        var start = StringUtils.hasText(properties.path()) ? root.resolve(properties.path()).normalize() : root;
        if (!start.startsWith(root) || !Files.isDirectory(start)) {
            throw new IOException("DDL path not found: " + properties.path());
        }
        var files = new ArrayList<DdlFile>();
        try (var paths = Files.walk(start)) {
            for (var path : (Iterable<Path>) paths::iterator) {
                var relative = root.relativize(path).toString().replace(File.separatorChar, '/');
                if (relative.startsWith(".git/") || !relative.toLowerCase(Locale.ROOT).endsWith(".sql")
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.size(path) > MAX_FILE_BYTES) {
                    log.warn("Skipping DDL file larger than {} bytes: {}", MAX_FILE_BYTES, relative);
                    continue;
                }
                files.add(new DdlFile(relative, Files.readString(path, StandardCharsets.UTF_8)));
            }
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        return files;
    }

    private <C extends TransportCommand<C, ?>> C authenticated(C command) {
        if (StringUtils.hasText(properties.token())) {
            var username = StringUtils.hasText(properties.username()) ? properties.username() : "x-access-token";
            command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, properties.token()));
        }
        return command;
    }
}
