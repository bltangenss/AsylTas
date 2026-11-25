package kz.asyltas.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import kz.asyltas.model.Necklace;

/** Пайдаланушы каталогына сақтайды; орнату бумасына жазбайды. */
public final class LocalStore {
  private final Path file;

  public LocalStore(Path file) {
    this.file = file;
  }

  public void load(Necklace necklace) throws IOException {
    if (Files.exists(file)) necklace.restore(Files.readString(file, StandardCharsets.UTF_8).trim());
  }

  public void save(Necklace necklace) throws IOException {
    Files.createDirectories(file.getParent());
    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(temp, necklace.encode(), StandardCharsets.UTF_8);
    try {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
