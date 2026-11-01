package de.opeldus.datacontract.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.opeldus.datacontract.artifact.GeneratedArtifact;
import de.opeldus.datacontract.security.CurrentUser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class GitPublisherTest {

    private static final CurrentUser.Identity ANNA = new CurrentUser.Identity("anna", "Anna", "anna@example.invalid");

    @TempDir
    Path temp;

    private Path remote;

    @BeforeEach
    void createRemote() throws Exception {
        remote = temp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remote.toFile()).setInitialBranch("main").call().close();
    }

    @Test
    void keepsCommitsThatOthersPushedInTheMeantime() throws Exception {
        var publisher = publisher(remote.toUri().toString());
        publisher.publish(List.of(new GeneratedArtifact("contracts/a/odcs.yaml", "a: 1\n")), "first", ANNA);
        pushFromAnotherClone("other.txt");

        publisher.publish(List.of(new GeneratedArtifact("contracts/b/odcs.yaml", "b: 1\n")), "second", ANNA);

        var clone = cloneRemote("verify");
        assertThat(clone.resolve("contracts/a/odcs.yaml")).exists();
        assertThat(clone.resolve("contracts/b/odcs.yaml")).exists();
        assertThat(clone.resolve("other.txt")).exists();
    }

    @Test
    void failedPublishIsReportedAndLeavesNothingBehindForTheNextAttempt() throws Exception {
        var unreachable = temp.resolve("missing.git").toUri().toString();
        var failing = publisher(unreachable);
        assertThatThrownBy(() -> failing.publish(
                List.of(new GeneratedArtifact("contracts/a/odcs.yaml", "a: 1\n")), "first", ANNA))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));

        var publisher = publisher(remote.toUri().toString());
        publisher.publish(List.of(new GeneratedArtifact("contracts/b/odcs.yaml", "b: 1\n")), "second", ANNA);

        var clone = cloneRemote("verify");
        assertThat(clone.resolve("contracts/a/odcs.yaml")).doesNotExist();
        assertThat(clone.resolve("contracts/b/odcs.yaml")).exists();
    }

    @Test
    void relativeRemotePathIsResolvedAgainstTheApplicationDirectory() throws Exception {
        var relative = Path.of("").toAbsolutePath().relativize(remote.toAbsolutePath());
        var publisher = publisher("./" + relative);

        publisher.publish(List.of(new GeneratedArtifact("contracts/a/odcs.yaml", "a: 1\n")), "first", ANNA);

        assertThat(cloneRemote("verify").resolve("contracts/a/odcs.yaml")).exists();
    }

    @Test
    void changedRemoteUrlIsAppliedToAnExistingWorkspace() throws Exception {
        var workdir = temp.resolve("shared-workspace").toString();
        var unreachable = temp.resolve("missing.git").toUri().toString();
        var failing = new GitPublisher(new GitProperties(workdir, unreachable, "main", "", "", "Bot", "bot@example.invalid"));
        assertThatThrownBy(() -> failing.publish(
                List.of(new GeneratedArtifact("contracts/a/odcs.yaml", "a: 1\n")), "first", ANNA))
                .isInstanceOf(ResponseStatusException.class);

        var publisher = new GitPublisher(new GitProperties(workdir, remote.toUri().toString(), "main", "", "", "Bot",
                "bot@example.invalid"));
        publisher.publish(List.of(new GeneratedArtifact("contracts/b/odcs.yaml", "b: 1\n")), "second", ANNA);

        assertThat(cloneRemote("verify").resolve("contracts/b/odcs.yaml")).exists();
    }

    @Test
    void artifactPathsCannotEscapeTheRepository() {
        var publisher = publisher(remote.toUri().toString());

        assertThatThrownBy(() -> publisher.publish(
                List.of(new GeneratedArtifact("../outside.yaml", "x: 1\n")), "bad", ANNA))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(temp.resolve("outside.yaml")).doesNotExist();
    }

    private GitPublisher publisher(String remoteUrl) {
        var workdir = temp.resolve("workspace-" + Math.abs(remoteUrl.hashCode())).toString();
        return new GitPublisher(new GitProperties(workdir, remoteUrl, "main", "", "", "Bot", "bot@example.invalid"));
    }

    private void pushFromAnotherClone(String file) throws Exception {
        var clone = temp.resolve("other");
        try (var git = Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(clone.toFile()).call()) {
            Files.writeString(clone.resolve(file), "x\n");
            git.add().addFilepattern(file).call();
            var person = new PersonIdent("Other", "other@example.invalid");
            git.commit().setMessage("other").setAuthor(person).setCommitter(person).setSign(false).call();
            git.push().call();
        }
    }

    private Path cloneRemote(String name) throws Exception {
        var clone = temp.resolve(name);
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(clone.toFile()).call().close();
        return clone;
    }
}
