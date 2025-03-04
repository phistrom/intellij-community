// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.roots.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.testFramework.ExtensionTestUtil;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.VfsTestUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.workspaceModel.core.fileIndex.impl.WorkspaceFileIndexEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.JavaResourceRootType;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.module.JpsModuleSourceRootType;

import java.io.File;
import java.io.IOException;
import java.util.*;

@HeavyPlatformTestCase.WrapInCommand
public class DirectoryIndexTest extends DirectoryIndexTestCase {
  private Module myModule2, myModule3;
  private VirtualFile myRootVFile;
  private VirtualFile myModule1Dir, myModule2Dir, myModule3Dir;
  private VirtualFile mySrcDir1, mySrcDir2;
  private SourceFolder mySrcDir1Folder, mySrcDir2Folder;
  private VirtualFile myTestSrc1;
  private SourceFolder myTestSrc1Folder;
  private VirtualFile myPack1Dir, myPack2Dir;
  private VirtualFile myFileLibDir, myFileLibSrc, myFileLibCls;
  private VirtualFile myLibAdditionalOutsideDir, myLibAdditionalOutsideSrcDir, myLibAdditionalOutsideExcludedDir, myLibAdditionalOutsideClsDir;
  private VirtualFile myLibDir, myLibSrcDir, myLibClsDir;
  private VirtualFile myLibAdditionalDir, myLibAdditionalSrcDir, myLibAdditionalSrcFile, myLibAdditionalExcludedDir, myLibAdditionalClsDir, myLibAdditionalClsFile;
  private VirtualFile myExcludeDir;
  private VirtualFile myResDir, myTestResDir;
  private SourceFolder myResDirFolder, myTestResDirFolder;
  private VirtualFile myExcludedLibSrcDir, myExcludedLibClsDir;

  @Override
  protected void setUp() throws Exception {
    super.setUp();

    final File root = createTempDirectory();

    ApplicationManager.getApplication().runWriteAction(() -> {
      /*
        root
            lib
                file.src
                file.cls
            additional-lib
                src
                excluded
                cls
            module1
                src1
                    pack1
                    testSrc
                        pack2
                res
                testRes
                lib
                    src
                      exc
                    cls
                      exc
                additional-lib
                    src
                    a.txt
                    excluded
                    cls
                module2
                    src2
                        CVS
                        excluded
            module3
            out
                module1
      */
      myRootVFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root);
      assertNotNull(myRootVFile);

      myFileLibDir = createChildDirectory(myRootVFile, "lib");
      myFileLibSrc = createChildData(myFileLibDir, "file.src");
      myFileLibCls = createChildData(myFileLibDir, "file.cls");
      myLibAdditionalOutsideDir = createChildDirectory(myRootVFile, "additional-lib");
      myLibAdditionalOutsideSrcDir = createChildDirectory(myLibAdditionalOutsideDir, "src");
      myLibAdditionalOutsideExcludedDir = createChildDirectory(myLibAdditionalOutsideDir, "excluded");
      myLibAdditionalOutsideClsDir = createChildDirectory(myLibAdditionalOutsideDir, "cls");
      myModule1Dir = createChildDirectory(myRootVFile, "module1");
      mySrcDir1 = createChildDirectory(myModule1Dir, "src1");
      myPack1Dir = createChildDirectory(mySrcDir1, "pack1");
      myTestSrc1 = createChildDirectory(mySrcDir1, "testSrc");
      myPack2Dir = createChildDirectory(myTestSrc1, "pack2");
      myResDir = createChildDirectory(myModule1Dir, "res");
      myTestResDir = createChildDirectory(myModule1Dir, "testRes");

      myLibDir = createChildDirectory(myModule1Dir, "lib");
      myLibSrcDir = createChildDirectory(myLibDir, "src");
      myExcludedLibSrcDir = createChildDirectory(myLibSrcDir, "exc");
      myLibAdditionalDir = createChildDirectory(myModule1Dir, "additional-lib");
      myLibAdditionalSrcDir = createChildDirectory(myLibAdditionalDir, "src");
      myLibAdditionalSrcFile = createChildData(myLibAdditionalDir, "a.txt");
      myLibAdditionalExcludedDir = createChildDirectory(myLibAdditionalDir, "excluded");
      myLibAdditionalClsDir = createChildDirectory(myLibAdditionalDir, "cls");
      myLibAdditionalClsFile = createChildDirectory(myLibAdditionalDir, "file.cls");
      myLibClsDir = createChildDirectory(myLibDir, "cls");
      myExcludedLibClsDir = createChildDirectory(myLibClsDir, "exc");
      myModule2Dir = createChildDirectory(myModule1Dir, "module2");
      mySrcDir2 = createChildDirectory(myModule2Dir, "src2");
      myExcludeDir = createChildDirectory(mySrcDir2, "excluded");

      myModule3Dir = createChildDirectory(myRootVFile, "module3");


      // fill roots of module1
      {
        ModuleRootModificationUtil.setModuleSdk(myModule, null);
        PsiTestUtil.addContentRoot(myModule, myModule1Dir);
        mySrcDir1Folder = PsiTestUtil.addSourceRoot(myModule, mySrcDir1);
        myTestSrc1Folder = PsiTestUtil.addSourceRoot(myModule, myTestSrc1, true);
        myResDirFolder = PsiTestUtil.addSourceRoot(myModule, myResDir, JavaResourceRootType.RESOURCE);
        myTestResDirFolder = PsiTestUtil.addSourceRoot(myModule, myTestResDir, JavaResourceRootType.TEST_RESOURCE);

        ModuleRootModificationUtil.addModuleLibrary(myModule, "lib.js",
                                                    Collections.singletonList(myFileLibCls.getUrl()), Collections
                                                      .singletonList(myFileLibSrc.getUrl()));
        PsiTestUtil.addExcludedRoot(myModule, myExcludedLibClsDir);
        PsiTestUtil.addExcludedRoot(myModule, myExcludedLibSrcDir);
      }

      // fill roots of module2
      {
        myModule2 = createJavaModuleWithContent(getProject(), "module2", myModule2Dir);

        PsiTestUtil.addContentRoot(myModule2, myModule2Dir);
        mySrcDir2Folder = PsiTestUtil.addSourceRoot(myModule2, mySrcDir2);
        PsiTestUtil.addExcludedRoot(myModule2, myExcludeDir);
        ModuleRootModificationUtil.addModuleLibrary(myModule2, "lib",
                                                    Collections.singletonList(myLibClsDir.getUrl()), Collections.singletonList(myLibSrcDir.getUrl()),
                                                    Arrays.asList(myExcludedLibClsDir.getUrl(), myExcludedLibSrcDir.getUrl()), DependencyScope.COMPILE, true);
      }

      ExtensionTestUtil.maskExtensions(AdditionalLibraryRootsProvider.EP_NAME, Collections.<AdditionalLibraryRootsProvider>singletonList(new AdditionalLibraryRootsProvider() {
                                          @NotNull
                                          @Override
                                          public Collection<SyntheticLibrary> getAdditionalProjectLibraries(@NotNull Project project) {
                                            return myProject == project ? Collections.singletonList(
                                              new JavaSyntheticLibrary(
                                                "test",
                                                ContainerUtil.newArrayList(myLibAdditionalSrcDir, myLibAdditionalOutsideSrcDir),
                                                ContainerUtil.newArrayList(myLibAdditionalClsDir, myLibAdditionalOutsideClsDir),
                                                ContainerUtil.newHashSet(myLibAdditionalExcludedDir, myLibAdditionalOutsideExcludedDir)
                                              )
                                            ) : Collections.emptyList();
                                          }
                                        }), getTestRootDisposable());

      // fill roots of module3
      {
        myModule3 = createJavaModuleWithContent(getProject(), "module3", myModule3Dir);

        PsiTestUtil.addContentRoot(myModule3, myModule3Dir);
        ModuleRootModificationUtil.addDependency(myModule3, myModule2);
      }
    });

    // to not interfere with previous test firing vfs events
    VirtualFileManager.getInstance().syncRefresh();
  }

  @Override
  protected void tearDown() throws Exception {
    myModule2 = null;
    myModule3 = null;
    super.tearDown();
  }

  public void testDirInfos() {
    assertNotInProject(myRootVFile);

    // beware: files in directory index
    checkInfo(myFileLibSrc, null, false, true, "", null, null, myModule);
    checkInfo(myFileLibCls, null, true, false, "", null, null, myModule);

    checkInfo(myLibAdditionalOutsideSrcDir, null, false, true, "", null, null);
    checkInfo(myLibAdditionalOutsideClsDir, null, true, false, "", null, null);
    assertExcludedFromProject(myLibAdditionalOutsideExcludedDir);
    assertIndexableContent(Arrays.asList(myLibAdditionalOutsideSrcDir, myLibAdditionalOutsideClsDir),
                           Collections.singletonList(myLibAdditionalOutsideExcludedDir));

    checkInfo(myModule1Dir, myModule, false, false, null, null, null);
    checkInfo(mySrcDir1, myModule, false, false, "", mySrcDir1Folder, JavaSourceRootType.SOURCE, myModule);
    checkInfo(myPack1Dir, myModule, false, false, "pack1", mySrcDir1Folder, JavaSourceRootType.SOURCE, myModule);
    checkInfo(myTestSrc1, myModule, false, false, "", myTestSrc1Folder, JavaSourceRootType.TEST_SOURCE, myModule);
    checkInfo(myPack2Dir, myModule, false, false, "pack2", myTestSrc1Folder, JavaSourceRootType.TEST_SOURCE, myModule);
    checkInfo(myResDir, myModule, false, false, "", myResDirFolder, JavaResourceRootType.RESOURCE, myModule);
    checkInfo(myTestResDir, myModule, false, false, "", myTestResDirFolder, JavaResourceRootType.TEST_RESOURCE, myModule);

    checkInfo(myLibDir, myModule, false, false, null, null, null);
    checkInfo(myLibSrcDir, myModule, false, true, "", null, null, myModule2, myModule3);
    checkInfo(myLibClsDir, myModule, true, false, "", null, null, myModule2, myModule3);

    assertEquals(myLibSrcDir, assertInProject(myLibSrcDir).getSourceRoot());

    checkInfo(myModule2Dir, myModule2, false, false, null, null, null);
    checkInfo(mySrcDir2, myModule2, false, false, "", mySrcDir2Folder, JavaSourceRootType.SOURCE, myModule2, myModule3);
    assertExcluded(myExcludeDir, myModule2);
    assertExcluded(myExcludedLibClsDir, myModule);
    assertExcluded(myExcludedLibSrcDir, myModule);

    assertEquals(myModule1Dir, assertInProject(myLibClsDir).getContentRoot());

    checkInfo(myModule3Dir, myModule3, false, false, null, null, null);
  }

  public void testDirsByPackageName() {
    checkPackage("", true, mySrcDir1, myTestSrc1, myResDir, myTestResDir, mySrcDir2, myLibSrcDir, myLibClsDir,
                 myLibAdditionalSrcDir, myLibAdditionalOutsideSrcDir, myLibAdditionalClsDir, myLibAdditionalOutsideClsDir);
    checkPackage("", false, mySrcDir1, myTestSrc1, myResDir, myTestResDir, mySrcDir2, myLibClsDir,
                 myLibAdditionalClsDir, myLibAdditionalOutsideClsDir);

    checkPackage("pack1", true, myPack1Dir);
    checkPackage("pack1", false, myPack1Dir);

    checkPackage("pack2", true, myPack2Dir);
    checkPackage("pack2", false, myPack2Dir);

    checkPackage(".pack2", false);
    checkPackage(".pack2", true);

    VirtualFile libClsPack = createChildDirectory(myLibClsDir, "pack1");
    VirtualFile libSrcPack = createChildDirectory(myLibSrcDir, "pack1");
    VirtualFile pack3Cls = createChildDirectory(myLibAdditionalClsDir, "pack3");
    VirtualFile pack3Src = createChildDirectory(myLibAdditionalSrcDir, "pack3");
    VirtualFile pack4Cls = createChildDirectory(myLibAdditionalOutsideClsDir, "pack4");
    VirtualFile pack4Src = createChildDirectory(myLibAdditionalOutsideSrcDir, "pack4");
    fireRootsChanged();
    checkPackage("pack1", true, myPack1Dir, libSrcPack, libClsPack);
    checkPackage("pack1", false, myPack1Dir, libClsPack);
    checkPackage("pack3", false, pack3Cls);
    checkPackage("pack3", true, pack3Src, pack3Cls);
    checkPackage("pack4", false, pack4Cls);
    checkPackage("pack4", true, pack4Src, pack4Cls);
  }

  public void testDirectoriesWithPackagePrefix() {
    PsiTestUtil.addSourceRoot(myModule3, myModule3Dir);
    WriteCommandAction.runWriteCommandAction(myProject, () -> {
      final ModifiableRootModel model = ModuleRootManager.getInstance(myModule3).getModifiableModel();
      model.getContentEntries()[0].getSourceFolders()[0].setPackagePrefix("pack1");
      model.commit();
    });
    checkPackage("pack1", true, myPack1Dir, myModule3Dir);
  }

  public void testPackageDirectoriesWithDots() {
    VirtualFile fooBar = createChildDirectory(mySrcDir1, "foo.bar");
    VirtualFile goo1 = createChildDirectory(fooBar, "goo");
    VirtualFile foo = createChildDirectory(mySrcDir2, "foo");
    VirtualFile bar = createChildDirectory(foo, "bar");
    VirtualFile goo2 = createChildDirectory(bar, "goo");

    checkPackage("foo", false, foo);
    checkPackage("foo.bar", false, bar, fooBar);
    checkPackage("foo.bar.goo", false, goo2, goo1);
  }
  
  private static OrderEntry[] toArray(Collection<OrderEntry> orderEntries) {
    return orderEntries.toArray(OrderEntry.EMPTY_ARRAY);
  }

  //everything except order entry checks is covered by NestedModuleAndLibraryRootsInProjectFileIndex 
  public void testModuleSourceAsLibrarySource() {
    ModuleRootModificationUtil.addModuleLibrary(myModule, "someLib", Collections.emptyList(), Collections.singletonList(mySrcDir1.getUrl()));

    checkInfo(mySrcDir1, myModule, false, true, "", mySrcDir1Folder, JavaSourceRootType.SOURCE, myModule, myModule);
    Collection<OrderEntry> entriesResult = myFileIndex.getOrderEntriesForFile(mySrcDir1);
    OrderEntry[] entries = toArray(entriesResult);

    assertInstanceOf(entries[0], LibraryOrderEntry.class);
    assertInstanceOf(entries[1], ModuleSourceOrderEntry.class);

    checkInfo(myTestSrc1, myModule, false, true, "testSrc", myTestSrc1Folder, JavaSourceRootType.TEST_SOURCE, myModule, myModule);
    entriesResult = myFileIndex.getOrderEntriesForFile(myTestSrc1);
    entries = toArray(entriesResult);
    assertInstanceOf(entries[0], LibraryOrderEntry.class);
    assertInstanceOf(entries[1], ModuleSourceOrderEntry.class);
  }

  //everything except order entry checks is covered by NestedModuleAndLibraryRootsInProjectFileIndex 
  public void testModuleSourceAsLibraryClasses() {
    ModuleRootModificationUtil.addModuleLibrary(myModule, "someLib", Collections.singletonList(mySrcDir1.getUrl()), Collections.emptyList());
    checkInfo(mySrcDir1, myModule, true, false, "", mySrcDir1Folder, JavaSourceRootType.SOURCE, myModule);
    assertInstanceOf(assertOneElement(toArray(myFileIndex.getOrderEntriesForFile(mySrcDir1))), ModuleSourceOrderEntry.class);
  }

  //everything except order entry checks is covered by NestedModuleRootsInProjectFileIndex 
  public void testModulesWithSameSourceContentRoot() {
    // now our API allows this (ReformatCodeActionTest), although UI doesn't. Maybe API shouldn't allow it as well?
    PsiTestUtil.addContentRoot(myModule2, myModule1Dir);
    PsiTestUtil.addSourceRoot(myModule2, mySrcDir1);

    checkInfo(myModule1Dir, myModule, false, false, null, null, null);
    checkInfo(mySrcDir1, myModule, false, false, "", mySrcDir1Folder, JavaSourceRootType.SOURCE, myModule3, myModule);
    checkInfo(myTestSrc1, myModule, false, false, "", myTestSrc1Folder, JavaSourceRootType.TEST_SOURCE, myModule3, myModule);
    checkInfo(myResDir, myModule, false, false, "", myResDirFolder, JavaResourceRootType.RESOURCE, myModule);

    checkInfo(mySrcDir2, myModule2, false, false, "", mySrcDir2Folder, JavaSourceRootType.SOURCE, myModule2, myModule3);
    assertEquals(myModule2Dir, myFileIndex.getContentRootForFile(mySrcDir2));
  }

  //everything except order entry checks is covered by NestedModuleRootsInProjectFileIndex 
  public void testModuleWithSameSourceRoot() {
    SourceFolder sourceFolder = PsiTestUtil.addSourceRoot(myModule2, mySrcDir1);
    checkInfo(mySrcDir1, myModule2, false, false, "", sourceFolder, JavaSourceRootType.SOURCE, myModule2, myModule3);
    checkInfo(myTestSrc1, myModule2, false, false, "testSrc", sourceFolder, JavaSourceRootType.SOURCE, myModule2, myModule3);
  }
  
  public void testProcessingNestedContentRootsOfExcludedDirsOnCreation() {
    String rootPath = myModule1Dir.getPath();
    final File f = new File(rootPath, "excludedDir/dir/anotherContentRoot");
    ApplicationManager.getApplication().runWriteAction(() -> {
      ModifiableRootModel rootModel = ModuleRootManager.getInstance(myModule).getModifiableModel();
      rootModel.getContentEntries()[0]
        .addExcludeFolder(VfsUtilCore.pathToUrl(f.getParentFile().getParent()));
      rootModel.commit();

      ModuleRootModificationUtil.addContentRoot(myModule2, FileUtil.toSystemIndependentName(f.getPath()));

      assertTrue(f.getPath(), f.exists() || f.mkdirs());
      LocalFileSystem.getInstance().refresh(false);
    });

    assertExcluded(LocalFileSystem.getInstance().findFileByIoFile(f.getParentFile().getParentFile()), myModule);
    assertInProject(LocalFileSystem.getInstance().findFileByIoFile(f));
  }

  public void testSyntheticLibraryInContent() {
    ModuleRootModificationUtil.addContentRoot(myModule, FileUtil.toSystemIndependentName(myModule1Dir.getPath()));
    checkInfo(myLibAdditionalDir, myModule, false, false, null, null, null);
    checkInfo(myLibAdditionalSrcDir, myModule, false, true, "", null, null);
    checkInfo(myLibAdditionalClsDir, myModule, true, false, "", null, null);
    checkInfo(myLibAdditionalExcludedDir, myModule, false, false, null, null, null);
    assertInProject(myLibAdditionalExcludedDir);
    assertIndexableContent(Arrays.asList(myLibAdditionalSrcDir, myLibAdditionalSrcFile, myLibAdditionalExcludedDir, myLibAdditionalClsDir, myLibAdditionalClsFile), null);
  }

  public void testLibraryDirInContent() {
    ModuleRootModificationUtil.addModuleLibrary(myModule, myModule1Dir.getUrl());

    checkInfo(myModule1Dir, myModule, true, false, "", null, null, myModule);
    checkInfo(mySrcDir1, myModule, true, false, "", mySrcDir1Folder, JavaSourceRootType.SOURCE, myModule);

    checkInfo(myModule2Dir, myModule2, true, false, "module2", null, null, myModule);
    checkInfo(mySrcDir2, myModule2, true, false, "", mySrcDir2Folder, JavaSourceRootType.SOURCE, myModule2, myModule3);
    checkInfo(myExcludeDir, null, true, false, "module2.src2.excluded", null, null, myModule3);

    checkInfo(myLibDir, myModule, true, false, "lib", null, null, myModule);
    checkInfo(myLibClsDir, myModule, true, false, "", null, null, myModule2, myModule3);

    //myModule is included into order entries instead of myModule2 because classes root for libraries dominates on source roots
    checkInfo(myLibSrcDir, myModule, true, true, "", null, null, myModule, myModule3);

    checkInfo(myResDir, myModule, true, false, "", myResDirFolder, JavaResourceRootType.RESOURCE, myModule);
    assertInstanceOf(assertOneElement(toArray(myFileIndex.getOrderEntriesForFile(myResDir))), ModuleSourceOrderEntry.class);

    if (WorkspaceFileIndexEx.IS_ENABLED) {
      assertExcluded(myExcludedLibSrcDir, myModule);
      assertExcluded(myExcludedLibClsDir, myModule);
    }
    else {
      checkInfo(myExcludedLibSrcDir, null, true, false, "lib.src.exc", null, null, myModule3, myModule);
      checkInfo(myExcludedLibClsDir, null, true, false, "lib.cls.exc", null, null, myModule3);
    }

    checkPackage("lib.src.exc", true, myExcludedLibSrcDir);
    checkPackage("lib.cls.exc", true, myExcludedLibClsDir);

    checkPackage("lib.src", true);
    checkPackage("lib.cls", true);

    checkPackage("exc", false);
    checkPackage("exc", true);
  }

  public void testFileContentRootsModifications() {
    assertNotInProject(myRootVFile);
    VirtualFile temp = createChildDirectory(myRootVFile, "temp");

    VirtualFile fileSourceRoot = createChildData(myRootVFile, "fileSourceRoot.txt");
    assertNotInProject(fileSourceRoot);

    PsiTestUtil.addContentRoot(myModule, fileSourceRoot);
    SourceFolder fileSourceFolder = PsiTestUtil.addSourceRoot(myModule, fileSourceRoot);
    checkInfo(fileSourceRoot, myModule, false, false, "", fileSourceFolder, JavaSourceRootType.SOURCE, myModule);
    assertTrue(myFileIndex.isInContent(fileSourceRoot));
    assertTrue(myFileIndex.isInSource(fileSourceRoot));

    // delete and recreate
    VfsTestUtil.deleteFile(fileSourceRoot);
    assertNotInProject(fileSourceRoot);
    assertFalse(myFileIndex.isInContent(fileSourceRoot));
    assertFalse(myFileIndex.isInSource(fileSourceRoot));
    fileSourceRoot = createChildData(myRootVFile, "fileSourceRoot.txt");
    checkInfo(fileSourceRoot, myModule, false, false, "", fileSourceFolder, JavaSourceRootType.SOURCE, myModule);
    assertTrue(myFileIndex.isInContent(fileSourceRoot));
    assertTrue(myFileIndex.isInSource(fileSourceRoot));

    // delete and move from another dir
    VfsTestUtil.deleteFile(fileSourceRoot);
    assertNotInProject(fileSourceRoot);
    assertFalse(myFileIndex.isInContent(fileSourceRoot));
    assertFalse(myFileIndex.isInSource(fileSourceRoot));
    fileSourceRoot = createChildData(temp, "fileSourceRoot.txt");
    assertNotInProject(fileSourceRoot);
    move(fileSourceRoot, myRootVFile);
    checkInfo(fileSourceRoot, myModule, false, false, "", fileSourceFolder, JavaSourceRootType.SOURCE, myModule);
    assertTrue(myFileIndex.isInContent(fileSourceRoot));
    assertTrue(myFileIndex.isInSource(fileSourceRoot));

    // delete and copy from another dir
    VfsTestUtil.deleteFile(fileSourceRoot);
    assertNotInProject(fileSourceRoot);
    assertFalse(myFileIndex.isInContent(fileSourceRoot));
    assertFalse(myFileIndex.isInSource(fileSourceRoot));
    fileSourceRoot = createChildData(temp, "fileSourceRoot.txt");
    assertNotInProject(fileSourceRoot);
    fileSourceRoot = copy(fileSourceRoot, myRootVFile, "fileSourceRoot.txt");
    checkInfo(fileSourceRoot, myModule, false, false, "", fileSourceFolder, JavaSourceRootType.SOURCE, myModule);
    assertTrue(myFileIndex.isInContent(fileSourceRoot));
    assertTrue(myFileIndex.isInSource(fileSourceRoot));

    // delete and rename from another file
    VfsTestUtil.deleteFile(fileSourceRoot);
    assertNotInProject(fileSourceRoot);
    assertFalse(myFileIndex.isInContent(fileSourceRoot));
    assertFalse(myFileIndex.isInSource(fileSourceRoot));
    fileSourceRoot = createChildData(myRootVFile, "temp_file.txt");
    assertNotInProject(fileSourceRoot);
    rename(fileSourceRoot, "fileSourceRoot.txt");
    checkInfo(fileSourceRoot, myModule, false, false, "", fileSourceFolder, JavaSourceRootType.SOURCE, myModule);
    assertTrue(myFileIndex.isInContent(fileSourceRoot));
    assertTrue(myFileIndex.isInSource(fileSourceRoot));
  }

  //everything except order entry checks is covered by ModuleRootsInProjectFileIndexTest 
  public void testSourceContentRootsUnderExcludedRoot() {
    VirtualFile contentRoot = createChildDirectory(myExcludeDir, "content");
    PsiTestUtil.addContentRoot(myModule2, contentRoot);
    checkInfo(contentRoot, myModule2, false, false, null, null, null, myModule2, myModule3);
    VirtualFile excludedFile = createChildData(myExcludeDir, "excluded.txt");

    VirtualFile sourceRoot = createChildDirectory(myExcludeDir, "src");
    VirtualFile sourceFile = createChildData(sourceRoot, "source.txt");
    SourceFolder sourceFolder = PsiTestUtil.addSourceRoot(myModule2, sourceRoot);
    assertEquals(myModule2Dir, assertInProject(sourceRoot).getContentRoot());
    checkInfo(sourceRoot, myModule2, false, false, "", sourceFolder, JavaSourceRootType.SOURCE, myModule2, myModule3);

    VirtualFile contentSourceRoot = createChildDirectory(myExcludeDir, "content-src");
    VirtualFile contentSourceFile = createChildData(sourceRoot, "content-source.txt");
    SourceFolder contentSourceFolder = PsiTestUtil.addSourceContentToRoots(myModule2, contentSourceRoot);
    checkInfo(contentSourceRoot, myModule2, false, false, "", contentSourceFolder, JavaSourceRootType.SOURCE, myModule2, myModule3);

    assertIteratedContent(myModule2,
                          Arrays.asList(sourceFile, contentSourceFile, sourceRoot, contentSourceRoot),
                          Arrays.asList(excludedFile, myExcludeDir));
  }

  //everything except order entry checks is covered by ModuleRootsInProjectFileIndexTest 
  public void testSourceContentRootsUnderExcludedRootUnderSourceRoot() {
    VirtualFile excluded = createChildDirectory(myModule2Dir, "excluded");
    PsiTestUtil.addExcludedRoot(myModule2, excluded);
    VirtualFile excludedFile = createChildData(excluded, "excluded.txt");

    VirtualFile contentRoot = createChildDirectory(excluded, "content");
    PsiTestUtil.addContentRoot(myModule2, contentRoot);
    checkInfo(contentRoot, myModule2, false, false, null, null, null);

    VirtualFile sourceRoot = createChildDirectory(excluded, "src");
    SourceFolder sourceFolder = PsiTestUtil.addSourceRoot(myModule2, sourceRoot);
    VirtualFile sourceFile = createChildData(sourceRoot, "source.txt");
    assertEquals(myModule2Dir, assertInProject(sourceRoot).getContentRoot());
    checkInfo(sourceRoot, myModule2, false, false, "", sourceFolder, JavaSourceRootType.SOURCE, myModule2, myModule3);

    VirtualFile contentSourceRoot = createChildDirectory(excluded, "content-src");
    VirtualFile contentSourceFile = createChildData(contentSourceRoot, "content-source.txt");
    SourceFolder contentSourceFolder = PsiTestUtil.addSourceContentToRoots(myModule2, contentSourceRoot);
    checkInfo(contentSourceRoot, myModule2, false, false, "", contentSourceFolder, JavaSourceRootType.SOURCE, myModule2, myModule3);

    assertIteratedContent(myModule2, Arrays.asList(sourceFile, contentSourceFile, sourceRoot, contentSourceRoot),
                          Arrays.asList(excludedFile, myExcludeDir));
  }

  private void checkInfo(VirtualFile file,
                         @Nullable Module module,
                         boolean isInLibraryClasses,
                         boolean isInLibrarySource,
                         @Nullable String packageName,
                         @Nullable SourceFolder moduleSourceFolder,
                         @Nullable final JpsModuleSourceRootType<?> moduleSourceRootType,
                         Module... modulesOfOrderEntries) {
    DirectoryInfo info = assertInProject(file);
    assertEquals(module, info.getModule());
    if (moduleSourceFolder != null || moduleSourceRootType != null) {
      assertTrue("isInModuleSource", info.isInModuleSource(file));
      assertEquals(moduleSourceFolder, myIndex.getSourceRootFolder(info));
      assertEquals(moduleSourceRootType, myIndex.getSourceRootType(info));
    }
    else {
      assertFalse("isInModuleSource", info.isInModuleSource(file));
    }
    assertEquals(isInLibraryClasses, info.hasLibraryClassRoot());
    assertEquals(isInLibrarySource, info.isInLibrarySource(file));
    assertEquals(isInLibraryClasses || isInLibrarySource, myFileIndex.isInLibrary(file));

    if (file.isDirectory()) {
      assertEquals(packageName, myFileIndex.getPackageNameByDirectory(file));
    }

    List<OrderEntry> orderEntries = myFileIndex.getOrderEntriesForFile(file);
    assertEquals(Arrays.toString(toArray(orderEntries)), modulesOfOrderEntries.length, toArray(orderEntries).length);
    for (Module aModule : modulesOfOrderEntries) {
      OrderEntry found = ModuleFileIndexImpl.findOrderEntryWithOwnerModule(aModule, orderEntries);
      assertNotNull("not found: " + aModule + " in " + Arrays.toString(toArray(orderEntries)), found);
    }
  }

  private void checkPackage(String packageName, boolean includeLibrarySources, VirtualFile... expectedDirs) {
    VirtualFile[] actualDirs = myIndex.getDirectoriesByPackageName(packageName, includeLibrarySources).toArray(VirtualFile.EMPTY_ARRAY);
    assertNotNull(actualDirs);
    Arrays.sort(actualDirs, Comparator.comparing(VirtualFile::getPath));
    Arrays.sort(expectedDirs, Comparator.comparing(VirtualFile::getPath));
    assertOrderedEquals(actualDirs, expectedDirs);

    for (VirtualFile dir : expectedDirs) {
      String actualName = myIndex.getPackageName(dir);
      assertEquals("Invalid package name for dir " + dir + ": " + packageName, packageName, actualName);
    }
  }

  public void testUnrelatedDirectoriesCreationMustNotLeadToDirectoryIndexRebuildToImproveCheckoutSpeed() {
    VirtualFile root = ModuleRootManager.getInstance(myModule).getContentRoots()[0];
    WriteAction.run(()->ModuleRootModificationUtil.updateModel(myModule, model -> {
      ContentEntry rootEntry = model.getContentEntries()[0];
      rootEntry.addSourceFolder(createChildDirectory(root, "extsrc"), false);
    }));

    RootIndex rootIndex = myIndex.getRootIndex();

    VirtualFile xxx = createChildDirectory(root, "xxx");
    assertFalse(ProjectFileIndex.getInstance(getProject()).isInSource(xxx));
    delete(xxx);
    assertSame(rootIndex, myIndex.getRootIndex());
  }

  public void testSourceRootResidingUnderExcludedDirectoryMustBeIndexed() throws IOException {
    VirtualFile contentDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(createTempDir("module"));

    Module module = createJavaModuleWithContent(getProject(), "module", contentDir);

    ApplicationManager.getApplication().runWriteAction(() -> {
      VirtualFile excludedDir = createChildDirectory(contentDir, "excluded");
      VirtualFile sourcesDir = createChildDirectory(excludedDir, "sources");
      createChildData(sourcesDir, "A.java");

      PsiTestUtil.addContentRoot(module, contentDir);
      PsiTestUtil.addExcludedRoot(module, excludedDir);
      PsiTestUtil.addSourceRoot(module, sourcesDir);
    });

    VirtualFile aJava = contentDir.findChild("excluded").findChild("sources").findChild("A.java");
    assertIndexableContent(Collections.singletonList(aJava), Collections.emptyList());
  }
}