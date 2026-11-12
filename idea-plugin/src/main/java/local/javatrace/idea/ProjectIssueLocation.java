package local.javatrace.idea;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 不能确定来源项目时不退回其他位置，以免把报错放错项目。 */
final class ProjectIssueLocation {
    private ProjectIssueLocation() {}
    static Path resolve(String basePath) throws IOException {
        if (basePath == null || basePath.isBlank()) throw new IOException("当前项目没有本地目录，无法保存项目记录。");
        Path root = Path.of(basePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("来源项目目录不存在，未改用其他保存位置。");
        return root.resolve(".java-trace").resolve("issues");
    }
}
