package bndtools.wizards.workspace;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import org.bndtools.core.ui.wizards.shared.TemplateSelectionWizardPage;
import org.bndtools.templating.Resource;
import org.bndtools.templating.ResourceMap;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IProjectNature;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkbenchWizard;

import aQute.lib.io.IO;
import bndtools.Plugin;

public class WorkspaceSetupWizard extends Wizard implements IWorkbenchWizard {

    private IWorkbench workbench;
    private IStructuredSelection selection;

    private WorkspaceSetupWizardPage setupPage;
    private TemplateSelectionWizardPage templatePage;
    private WorkspacePreviewPage previewPage;

    public WorkspaceSetupWizard() {
        setNeedsProgressMonitor(true);
    }

    @Override
    public void init(IWorkbench workbench, IStructuredSelection selection) {
        this.workbench = workbench;
        this.selection = selection;

        setupPage = new WorkspaceSetupWizardPage();
        updateSetupPageForExistingProjects();
        templatePage = new TemplateSelectionWizardPage("workspaceTemplateSelection", "workspace", null);
        templatePage.setTitle("Select Workspace Template");
        previewPage = new WorkspacePreviewPage();
        previewPage.setTargetDir(setupPage.getLocation().toFile());

        setupPage.addPropertyChangeListener(WorkspaceLocationPart.PROP_LOCATION, new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                previewPage.setTargetDir(setupPage.getLocation().toFile());
            }
        });
        templatePage.addPropertyChangeListener(TemplateSelectionWizardPage.PROP_TEMPLATE, new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                previewPage.setTemplate(templatePage.getTemplate());
            }
        });
    }

    @Override
    public void addPages() {
        addPage(setupPage);
        addPage(templatePage);
        addPage(previewPage);
    }

    @Override
    public boolean performFinish() {
        final IWorkspace workspace = ResourcesPlugin.getWorkspace();

        final File targetDir = previewPage.getTargetDir();
        final Set<String> checkedPaths = previewPage.getCheckedPaths();

        try {
            // Expand the template
            ResourceMap outputs = previewPage.getTemplateOutputs();
            final Set<File> topLevelFolders = new HashSet<>();
            for (Entry<String,Resource> entry : outputs.entries()) {
                String path = entry.getKey();
                if (checkedPaths.contains(path)) {
                    Resource resource = entry.getValue();

                    // Create the folder or file resource
                    File file = new File(targetDir, path);
                    switch (resource.getType()) {
                    case Folder :
                        Files.createDirectories(file.toPath());
                        break;
                    case File :
                        File parentDir = file.getParentFile();
                        Files.createDirectories(parentDir.toPath());
                        try (InputStream in = resource.getContent(); FileOutputStream out = new FileOutputStream(file)) {
                            IO.copy(in, out);
                        }
                        break;
                    default :
                        throw new IllegalArgumentException("Unknown resource type " + resource.getType());
                    }

                    // Remember the top-level folders we create, for importing below
                    if (file.getParentFile().equals(targetDir))
                        topLevelFolders.add(file);
                }
            }

            // Import anything that looks like an Eclipse project
            final IWorkspaceRunnable importProjectsRunnable = new IWorkspaceRunnable() {
                @Override
                public void run(IProgressMonitor monitor) throws CoreException {
                    File[] children = targetDir.listFiles();
                    if (children != null) {
                        SubMonitor progress = SubMonitor.convert(monitor, children.length * 2);
                        for (File folder : children) {
                            if (folder.isDirectory() && topLevelFolders.contains(folder)) {
                                String projectName = folder.getName();
                                File projectFile = new File(folder, IProjectDescription.DESCRIPTION_FILE_NAME);
                                if (projectFile.exists()) {
                                    IProject project = workspace.getRoot().getProject(projectName);
                                    if (!project.exists()) {
                                        // No existing project in the workspace, so import the generated project.
                                        project.create(progress.newChild(1));
                                        project.open(progress.newChild(1));
                                    } else {
                                        // If a project with the same name exists, does it live in the same location? If not, we can't import the generated project.
                                        File existingLocation = project.getLocation().toFile();
                                        if (!existingLocation.equals(folder)) {
                                            String message = String.format("Cannot import generated project from %s. A project named %s already exists in the workspace and is mapped to location %s", folder.getAbsolutePath(), projectName,
                                                    existingLocation);
                                            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, message, null));
                                        }

                                        // Open it if closed
                                        project.open(progress.newChild(1));

                                        // Refresh, as the template may have generated new content
                                        project.refreshLocal(IResource.DEPTH_INFINITE, progress.newChild(1));
                                    }
                                }
                            }
                        }
                    }
                }
            };
            getContainer().run(true, true, new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                    try {
                        workspace.run(importProjectsRunnable, monitor);
                    } catch (CoreException e) {
                        throw new InvocationTargetException(e);
                    }
                }
            });

            // Prompt to switch to the bndtools perspective
            if (MessageDialog.openQuestion(getShell(), "Bndtools Perspective", "Switch to the Bndtools perspective?")) {
                IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
                workbench.showPerspective("bndtools.perspective", window);
            }
            return true;
        } catch (InvocationTargetException e) {
            ErrorDialog.openError(getShell(), "Error", null, new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error generating template output", e.getTargetException()));
            return false;
        } catch (Exception e) {
            ErrorDialog.openError(getShell(), "Error", null, new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error generating template output", e));
            return false;
        }

    }

    private void updateSetupPageForExistingProjects() {
        // find existing bnd projects
        File existingBndLocation = null;
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!project.isOpen())
                continue;

            try {
                IProjectNature bndNature = project.getNature(Plugin.BNDTOOLS_NATURE);
                if (bndNature != null) {
                    File projectLocation = project.getLocation().toFile();
                    existingBndLocation = projectLocation.getParentFile();
                    break;
                }
            } catch (CoreException ex) {
                Plugin.getDefault().getLog().log(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Failed to load project nature", ex));
            }
        }
        if (existingBndLocation != null) {
            setupPage.setLocation(new LocationSelection(false, existingBndLocation.getAbsolutePath()));
        }
    }

}