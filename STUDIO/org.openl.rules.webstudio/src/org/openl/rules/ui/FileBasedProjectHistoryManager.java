package org.openl.rules.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import org.openl.rules.project.instantiation.ReloadType;
import org.openl.source.SourceHistoryManager;
import org.openl.util.CollectionUtils;
import org.openl.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Andrei Astrouski
 */
public class FileBasedProjectHistoryManager implements SourceHistoryManager<File> {

    private final Logger log = LoggerFactory.getLogger(FileBasedProjectHistoryManager.class);

    private ProjectModel projectModel;
    private final String storagePath;

    private static final String REVISION_VERSION = "Revision Version";

    FileBasedProjectHistoryManager(ProjectModel projectModel, String storagePath, Integer maxFilesInStorage) {
        if (projectModel == null) {
            throw new IllegalArgumentException();
        }
        if (storagePath == null) {
            throw new IllegalArgumentException();
        }
        this.storagePath = storagePath;
        this.projectModel = projectModel;
        if (maxFilesInStorage != null) {
            delete(maxFilesInStorage);
        }
    }

    private void delete(int count) {
        List<File> files = new ArrayList<>();
        try {
            // Revision version must always exist, but it is not included in the total number of changes
            Files.walkFileTree(Paths.get(storagePath), new HashSet<>(), 1, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    FileVisitResult fileVisitResult = super.visitFile(file, attrs);
                    File f = file.toFile();
                    if (f.isDirectory() && !REVISION_VERSION.equals(f.getName())) {
                        files.add(f);
                    }
                    return fileVisitResult;
                }
            });
            if (files != null && files.size() > (count)) {
                Collections.sort(files);
                for (int i = 0; i < files.size() - count; i++) {
                    File file = files.get(i);
                    FileUtils.delete(file);
                }
            }
        } catch (Exception e) {
            log.error("Cannot delete history", e);
        }
    }

    @Override
    public synchronized void save(File source) {
        if (source == null) {
            throw new IllegalArgumentException();
        }
        long currentDate = System.currentTimeMillis();
        String name = projectModel.getModuleInfo().getName();
        String destFilePath = Paths.get(storagePath, String.valueOf(currentDate)).toString();
        File destFile = new File(destFilePath, name + "." + org.openl.util.FileUtils.getExtension(source.getName()));
        try {
            FileUtils.copy(source, destFile);
            destFile.setLastModified(currentDate);
        } catch (Exception e) {
            log.error("Cannot add file {}", name, e);
        }
    }

    @Override
    public File get(long date) {
        File[] commit;
        if (date == 0) {
            commit = new File(storagePath, REVISION_VERSION).listFiles();
        } else {
            commit = new File(storagePath, String.valueOf(date)).listFiles();
        }
        return commit != null && commit.length == 1 ? commit[0] : null;
    }

    @Override
    public void init() {
        File source = projectModel.getCurrentModuleWorkbook().getSourceFile();
        synchronized (this) {
            String projectName = projectModel.getModuleInfo().getName();
            File storageDir = new File(storagePath);
            File[] files = storageDir.listFiles();
            if (files != null) {
                for (File changeDir : files) {
                    File[] names = changeDir.listFiles((dir, name) -> projectName.equals(FileUtils.getBaseName(name)));
                    if (CollectionUtils.isNotEmpty(names)) {
                        return;
                    }
                }
            }
        }
        String name = projectModel.getModuleInfo().getName();
        String destFilePath = Paths.get(storagePath, REVISION_VERSION).toString();
        File destFile = new File(destFilePath, name + "." + org.openl.util.FileUtils.getExtension(source.getName()));
        try {
            FileUtils.copy(source, destFile);
        } catch (Exception e) {
            log.error("Cannot add file {}", name, e);
        }
    }

    @Override
    public void restore(long date) throws Exception {
        File fileToRestore = get(date);
        if (fileToRestore != null) {
            File currentSourceFile = projectModel.getCurrentModuleWorkbook().getSourceFile();
            try {
                FileUtils.copy(fileToRestore, currentSourceFile);
                projectModel.reset(ReloadType.FORCED);
                projectModel.buildProjectTree();
                log.info("Project was restored successfully");
            } catch (Exception e) {
                log.error("Cannot restore project at {}", new SimpleDateFormat().format(new Date(date)));
                throw e;
            }
        }
    }

}
