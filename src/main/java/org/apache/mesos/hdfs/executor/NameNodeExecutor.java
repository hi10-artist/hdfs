package org.apache.mesos.hdfs.executor;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.mesos.ExecutorDriver;
import org.apache.mesos.MesosExecutorDriver;
import org.apache.mesos.Protos.*;
import org.apache.mesos.hdfs.config.SchedulerConf;
import org.apache.mesos.hdfs.executor.AbstractNodeExecutor.TimedHealthCheck;
import org.apache.mesos.hdfs.util.HDFSConstants;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.Timer;

import java.io.File;
import java.util.TimerTask;

/**
 * The executor for the Primary Name Node Machine.
 **/
public class NameNodeExecutor extends AbstractNodeExecutor {
  public static final Log log = LogFactory.getLog(NameNodeExecutor.class);

  // Tasks run by executor
  private Task nameNodeTask;
  // TODO better handling in livestate and persistent state of zkfc task. Right now they are
  // chained.
  private Task zkfcNodeTask;
  private Task journalNodeTask;

  /**
   * The constructor for the primary name node which saves the configuration.
   **/
  @Inject
  NameNodeExecutor(SchedulerConf schedulerConf) {
    super(schedulerConf);
  }

  /**
   * Main method for executor, which injects the configuration and state and starts the driver.
   */
  public static void main(String[] args) {
    Injector injector = Guice.createInjector();
    MesosExecutorDriver driver = new MesosExecutorDriver(
        injector.getInstance(NameNodeExecutor.class));
    System.exit(driver.run() == Status.DRIVER_STOPPED ? 0 : 1);
  }

  /**
   * Add tasks to the task list and then start the tasks in the following order : 1) Start Journal
   * Node 2) Receive Activate Message 3) Start Name Node 4) Start ZKFC Node
   **/
  @Override
  public void launchTask(final ExecutorDriver driver, final TaskInfo taskInfo) {
    executorInfo = taskInfo.getExecutor();
    Task task = new Task(taskInfo);
    if (taskInfo.getTaskId().getValue().contains(HDFSConstants.JOURNAL_NODE_ID)) {
      journalNodeTask = task;
      // Start the journal node
      startProcess(driver, journalNodeTask);
      driver.sendStatusUpdate(TaskStatus.newBuilder()
          .setTaskId(journalNodeTask.taskInfo.getTaskId())
          .setState(TaskState.TASK_RUNNING)
          .build());
    } else if (taskInfo.getTaskId().getValue().contains(HDFSConstants.NAME_NODE_TASKID)) {
      nameNodeTask = task;
      driver.sendStatusUpdate(TaskStatus.newBuilder()
          .setTaskId(nameNodeTask.taskInfo.getTaskId())
          .setState(TaskState.TASK_RUNNING)
          .build());
    } else if (taskInfo.getTaskId().getValue().contains(HDFSConstants.ZKFC_NODE_ID)) {
      zkfcNodeTask = task;
      driver.sendStatusUpdate(TaskStatus.newBuilder()
          .setTaskId(zkfcNodeTask.taskInfo.getTaskId())
          .setState(TaskState.TASK_RUNNING)
          .build());
    }
  }

  @Override
  public void killTask(ExecutorDriver driver, TaskID taskId) {
    log.info("Killing task : " + taskId.getValue());
    Task task = null;
    if (taskId.getValue().contains(HDFSConstants.JOURNAL_NODE_ID)) {
      task = journalNodeTask;
    } else if (taskId.getValue().contains(HDFSConstants.NAME_NODE_TASKID)) {
      task = nameNodeTask;
    } else if (taskId.getValue().contains(HDFSConstants.ZKFC_NODE_ID)) {
      task = zkfcNodeTask;
    }

    if (task != null && task.process != null) {
      task.process.destroy();
      task.process = null;
      sendTaskFailed(driver, task);
    }
  }

  @Override
  public void frameworkMessage(ExecutorDriver driver, byte[] msg) {
    super.frameworkMessage(driver, msg);
    String messageStr = new String(msg);
    File nameDir = new File(schedulerConf.getDataDir() + "/name");
    if (messageStr.equals(HDFSConstants.NAME_NODE_INIT_MESSAGE)
        || messageStr.equals(HDFSConstants.NAME_NODE_BOOTSTRAP_MESSAGE)) {
      if (nameDir.exists()) {
        log.info(String
            .format("NameNode data directory %s already exists, not formatting just starting",
                nameDir));
      } else {
        nameDir.mkdirs();
      }
      // Start the zkfc node
      startProcess(driver, zkfcNodeTask);
      driver.sendStatusUpdate(TaskStatus.newBuilder()
          .setTaskId(nameNodeTask.taskInfo.getTaskId())
          .setState(TaskState.TASK_RUNNING)
          .setMessage(messageStr)
          .build());
      // start the namenode task
      driver.sendStatusUpdate(TaskStatus.newBuilder()
          .setTaskId(nameNodeTask.taskInfo.getTaskId())
          .setState(TaskState.TASK_RUNNING)
          .build());
      if (schedulerConf.usingMesosDns()) {
        // need to wait for other nodes to be available.
        if (messageStr.equals(HDFSConstants.NAME_NODE_INIT_MESSAGE)) {
          PreNNInitTask checker = new PreNNInitTask(driver, nameNodeTask);
          timer.scheduleAtFixedRate(checker, 0, 15000);
        } else if (messageStr.equals(HDFSConstants.NAME_NODE_BOOTSTRAP_MESSAGE)) {
          PreNNBootstrapTask checker = new PreNNBootstrapTask(driver, nameNodeTask);
          timer.scheduleAtFixedRate(checker, 0, 15000);
        }
      } else {
        runCommand(driver, nameNodeTask, "bin/hdfs-mesos-namenode " + messageStr);
        startProcess(driver, nameNodeTask);
      }
    }
  }

  /**
   * This class is designed to help launch the primary namenode in its init phase. The node needs
   * the journal nodes to be available on port 8485. In Mesos-DNS, this takes an uncertain amount of
   * time, so we must schedule it asynchronously.
   */
  private class PreNNInitTask extends TimerTask {
    ExecutorDriver driver;
    Task task;

    public PreNNInitTask(ExecutorDriver driver, Task task) {
      this.driver = driver;
      this.task = task;
    }

    @Override
    public void run() {
      boolean success = true;
      for (int i = schedulerConf.getJournalNodeCount(); i > 0; i--) {
        String host = HDFSConstants.JOURNAL_NODE_ID + i + "." + schedulerConf.getFrameworkName() + "." + schedulerConf.getMesosDnsDomain();
        log.info("Checking for " + host);
        try (Socket connected = new Socket(host, HDFSConstants.JOURNAL_NODE_HEALTH_PORT)) {
          log.info("Successfully found " + host + " at port " + HDFSConstants.JOURNAL_NODE_HEALTH_PORT);
        } catch (SecurityException | IOException e) {
          log.info("Couldn't resolve host " + host + " at port " + HDFSConstants.JOURNAL_NODE_HEALTH_PORT);
          success = false;
          break;
        }
      }
      if (success) {
        log.info("Successfully found all nodes needed to continue. Sending message: " + HDFSConstants.NAME_NODE_INIT_MESSAGE);
        this.cancel();
        runCommand(driver, task, "bin/hdfs-mesos-namenode " + HDFSConstants.NAME_NODE_INIT_MESSAGE);
        startProcess(driver, task);
      }
    }
  }

  /**
   * This class is designed to help launch the secondary namenode in its bootstrap phase. The node
   * needs both name nodes to be available as DNS entries. In Mesos-DNS, this takes an uncertain
   * amount of time, so we must schedule it asynchronously.
   */
  private class PreNNBootstrapTask extends TimerTask {
    ExecutorDriver driver;
    Task task;

    public PreNNBootstrapTask(ExecutorDriver driver, Task task) {
      this.driver = driver;
      this.task = task;
    }

    @Override
    public void run() {
      boolean success = true;
      for (int i = HDFSConstants.TOTAL_NAME_NODES; i > 0; i--) {
        String host = HDFSConstants.NAME_NODE_ID + i + "." + schedulerConf.getFrameworkName() + "." + schedulerConf.getMesosDnsDomain();
        log.info("Checking for " + host);
        try {
          InetAddress.getByName(host);
          log.info("Successfully found " + host);
        } catch (SecurityException | IOException e) {
          log.info("Couldn't resolve host " + host);
          success = false;
          break;
        }
      }
      if (success) {
        log.info("Successfully found all nodes needed to continue. Sending message: " + HDFSConstants.NAME_NODE_BOOTSTRAP_MESSAGE);
        this.cancel();
        runCommand(driver, task, "bin/hdfs-mesos-namenode " + HDFSConstants.NAME_NODE_BOOTSTRAP_MESSAGE);
        startProcess(driver, task);
      }
    }
  }
}
