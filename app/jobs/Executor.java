package jobs;

import java.util.*;
import java.io.*;

import play.*;
import play.jobs.*;
import play.libs.*;

import models.*;
import notifiers.*;


@Every("${cron.checkInterval}")
public class Executor extends Job {

    long projectId = 0;

    public void doJob() throws Exception {

        Project project = Project.findById(++projectId);

        // No more project, restart the queue
        if (project == null) {
            projectId = 0;
            return;
        }

        Logger.info("");
        Logger.info("It's time to test %s", project.name);

        // Update
        if (project.updateCommand != null && project.updateCommand.trim().length() > 0) {
            Logger.info("-> Updating...");
            String command = project.updateCommand;
            Logger.info("-> Executing " + command);
            execute(command);
        }

        // Get current version number
        String version = null;
        if (project.versionCommand != null && project.versionCommand.trim().length() > 0) {
            String command = project.versionCommand.replace("%path", project.path);
            version = getResult(command).trim();
            Logger.info("-> Executing " + command);
            Logger.info("-> Version is %s", version);
        } else {
            Logger.warn("-> Project %s has no version command... Skipping", project.name);
            return;
        }

        // Compute id
        int id = Math.abs((project.name + version).hashCode());

        // Check if test results exists
        File results = Play.getFile("results/" + id);
        if (results.exists()) {
            Logger.info("-> Already tested... Skipping");
            return;
        }

        Logger.info("-> Testing... ");
        execute((project.runInXvfb ? "xvfb-run " : "") + project.framework + "/play auto-test " + project.path);
        Logger.info("-> Done!");

        // Check the result
        results.mkdirs();
        Files.copyDir(new File(project.path, "test-result"), results);
        Files.copyDir(new File(project.path, "test-result"), results);

        boolean passed = new File(results, "result.passed").exists();
        File file = new File(results, "application.log");
        LineNumberReader reader = new LineNumberReader(new FileReader(file));
        String result = "";
        String error;
        boolean start = false;
        // Only get from the exception
        while ((error = reader.readLine()) != null) {
            if (!start) {
                start = error.contains("Exception");
            }
            if (start) {
                result += error + "\n";
            }
        }
        new Result(id + "", project.name, project.versionPattern.replace("%version", version), project.revisionDetailPattern.replace("%version", version), passed).save();

        // Send notification
        Notifier.sendResult(id, project.name, version, passed, project.notifications, result);

    }

    // ~~~~

    public static String getResult(String command) throws Exception {
        Process p = Runtime.getRuntime().exec(command);
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        StringBuffer buffer = new StringBuffer();
        String line = null;
        while ((line = reader.readLine()) != null) {
            buffer.append(line);
            buffer.append("\n");
        }
        p.waitFor();
        return buffer.toString();
    }

    public static int execute(String command) throws Exception {
        Process p = Runtime.getRuntime().exec(command);
        return p.waitFor();
    }

}

