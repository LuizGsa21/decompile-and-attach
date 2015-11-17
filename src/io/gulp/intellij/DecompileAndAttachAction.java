package io.gulp.intellij;

import java.io.*;
import java.util.Arrays;
import java.util.function.Function;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.codec.Charsets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.java.decompiler.IdeaDecompiler;

import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.roots.ui.configuration.PathUIUtils;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * Created by bduisenov on 12/11/15.
 */
public class DecompileAndAttachAction extends AnAction {

    private static final Logger logger = Logger.getInstance(DecompileAndAttachAction.class);

    private String baseDirName = "decompiled";

    @Override
    public void update(AnActionEvent e) {
        Presentation presentation = e.getPresentation();
        presentation.setEnabled(false);
        presentation.setVisible(false);
        VirtualFile virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (virtualFile != null && //
                "jar".equals(virtualFile.getExtension()) && //
                e.getProject() != null) {
            presentation.setEnabled(true);
            presentation.setVisible(true);
        }
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();

        if (project == null) {
            return;
        }

        findOrCreateBaseDir(project) //
                .done((baseDir) -> {
                    VirtualFile virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE);
                    if ("jar".equals(virtualFile.getExtension())) {
                        new Task.Backgroundable(project, "Decompiling...", false) {

                            @Override
                            public void run(@NotNull ProgressIndicator indicator) {
                                indicator.setFraction(0.0);
                                VirtualFile jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(virtualFile);
                                try {
                                    File tmpJarFile = FileUtil.createTempFile("decompiled", "tmp");
                                    String filename;
                                    try (JarOutputStream jarOutputStream = createJarOutputStream(tmpJarFile)) {
                                        filename = processor(jarOutputStream).apply(jarRoot);
                                    }
                                    indicator.setFraction(.90);
                                    indicator.setText("Attaching decompiled sources to project");
                                    copyAndAttach(project, baseDir, tmpJarFile, filename);
                                } catch (IOException e) {
                                    Throwables.propagate(e);
                                }
                                indicator.setFraction(1.0);
                            }
                        }.queue();
                    } else {
                        Messages.showErrorDialog("You must choose jar file.", "Invalid File");
                    }
                });
    }

    private void copyAndAttach(Project project, VirtualFile baseDir, File tmpJarFile, String filename) throws IOException {
        String libraryName = filename.replace(".jar", "-sources.jar");
        File sourceJar = new File(baseDir.getPath() + File.separator + libraryName);
        boolean newFile = sourceJar.createNewFile();
        logger.debug("file exists?: ", newFile);
        FileUtil.copy(tmpJarFile, sourceJar);
        FileUtil.delete(tmpJarFile);
        if (newFile) {
            // library is added to project for a first time
            ApplicationManager.getApplication().invokeAndWait(() -> {
                VirtualFile sourceJarVF = LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(sourceJar);
                VirtualFile sourceJarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(sourceJarVF);
                VirtualFile[] roots = PathUIUtils.scanAndSelectDetectedJavaSourceRoots(null, new VirtualFile[]{sourceJarRoot});
                new WriteCommandAction<Void>(project) {
                    @Override
                    protected void run(@NotNull Result<Void> result) throws Throwable {
                        Library library = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                                .createLibrary(libraryName);
                        Library.ModifiableModel model = library.getModifiableModel();
                        for (VirtualFile root : roots) {
                            model.addRoot(root, OrderRootType.SOURCES);
                        }
                        model.commit();
                    }
                }.execute();
            }, ModalityState.NON_MODAL);
        }
        new Notification("DecompileAndAttach", "Jar Sources Added", "decompiled sources " + libraryName + " where added successfully",
                NotificationType.INFORMATION).notify(project);
    }

    private Function<VirtualFile, String> processor(JarOutputStream jarOutputStream) {
        return new Function<VirtualFile, String>() {

            private IdeaDecompiler decompiler = new IdeaDecompiler();

            @Override
            public String apply(VirtualFile head) {
                try {
                    VirtualFile[] children = head.getChildren();
                    process("", head.getChildren()[0], Iterables.skip(Arrays.asList(children), 1));
                    return head.getName(); // file name
                } catch (IOException e) {
                    Throwables.propagate(e);
                }
                return null;
            }

            private void process(final String relativePath, VirtualFile head, Iterable<VirtualFile> tail) throws IOException {
                if (head == null) {
                    return;
                }
                VirtualFile[] children = head.getChildren();
                if (head.isDirectory() && children.length > 0) {
                    String path = relativePath + "/" + head.getName() + "/";
                    Iterable<VirtualFile> xs = Iterables.skip(Arrays.asList(children), 1);
                    process(path, children[0], xs);
                } else {
                    if (!head.getName().contains("$") && "class".equals(head.getExtension())) {
                        decompileAndSave(relativePath + head.getNameWithoutExtension() + ".java", head);
                    }
                }
                if (tail != null && !Iterables.isEmpty(tail)) {
                    process(relativePath, Iterables.getFirst(tail, null), Iterables.skip(tail, 1));
                }
            }

            private void decompileAndSave(String relativeFilePath, VirtualFile file) throws IOException {
                CharSequence decompiled = decompiler.getText(file);
                addFileEntry(jarOutputStream, relativeFilePath, decompiled);
            }
        };
    }

    private Promise<VirtualFile> findOrCreateBaseDir(Project project) {
        AsyncPromise<VirtualFile> promise = new AsyncPromise<>();
        ApplicationManager.getApplication().runWriteAction(() -> { //
            VirtualFile baseDir = Arrays.stream(project.getBaseDir().getChildren()) //
                    .filter(vf -> vf.getName().equals(baseDirName)) //
                    .findFirst().orElseGet(() -> {
                try {
                    return project.getBaseDir().createChildDirectory(null, baseDirName);
                } catch (IOException e) {
                    promise.setError(e);
                }
                return null;
            });
            if (promise.getState() != Promise.State.REJECTED) {
                promise.setResult(baseDir);
            }
        });
        return promise;
    }

    private static JarOutputStream createJarOutputStream(File jarFile) throws IOException {
        BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(jarFile));
        return new JarOutputStream(outputStream);
    }

    private static void addFileEntry(ZipOutputStream output, String relativePath, CharSequence decompiled) throws IOException {
        ByteArrayInputStream file = new ByteArrayInputStream(
                decompiled.toString().getBytes(Charsets.toCharset("UTF-8")));
        long size = decompiled.length();
        ZipEntry e = new ZipEntry(relativePath);
        if (size == 0) {
            e.setMethod(ZipEntry.STORED);
            e.setSize(0);
            e.setCrc(0);
        }
        output.putNextEntry(e);
        try {
            FileUtil.copy(file, output);
        }
        finally {
            file.close();
        }
        output.closeEntry();
    }

}
