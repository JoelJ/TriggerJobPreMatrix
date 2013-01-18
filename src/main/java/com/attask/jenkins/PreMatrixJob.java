package com.attask.jenkins;

import hudson.Extension;
import hudson.Util;
import hudson.matrix.*;
import hudson.model.*;
import hudson.model.queue.QueueTaskFuture;
import jenkins.model.Jenkins;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.tools.ant.filters.StringInputStream;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

/**
 * User: Joel Johnson
 * Date: 1/15/13
 * Time: 4:31 PM
 */
public class PreMatrixJob extends DefaultMatrixExecutionStrategyImpl {
	private static final Logger log = Logger.getLogger("TriggerJobPreMatrix");

	private String preJobName;
	private String preJobParameters;
	private String prePropertiesFileToInject;

	private String postJobName;
	private String postJobParameters;
	private String postPropertiesFileToInject;
	private static final int NUM_RETRIES = 5;

	@DataBoundConstructor
	public PreMatrixJob(String preJobName, String preJobParameters, String prePropertiesFileToInject,
						String postJobName, String postJobParameters, String postPropertiesFileToInject) {
		super(false, false, "", null, new NoopMatrixConfigurationSorter());

		this.preJobName = preJobName == null ? "" : postJobName.trim();
		this.preJobParameters = preJobParameters;
		this.prePropertiesFileToInject = prePropertiesFileToInject;

		this.postJobName = postJobName == null ? "" : postJobName.trim();
		this.postJobParameters = postJobParameters;
		this.postPropertiesFileToInject = postPropertiesFileToInject;
	}

	@Override
	public Result run(MatrixBuild.MatrixBuildExecution execution) throws InterruptedException, IOException {

		BuildListener listener = execution.getListener();
		Run build = execution.getBuild();

		Result result = Result.SUCCESS;
		if (!preJobName.isEmpty()) {
			result = preRun(build, listener);
		}
		if(result.isBetterThan(Result.FAILURE)) {
			try {
				Result runResult = super.run(execution);
				if(runResult.isWorseThan(result)) {
					result = runResult;
				}
			} finally {
				if (!postJobName.isEmpty()) {
					Result postResult = postRun(build, listener);
					if(postResult.isWorseThan(result)) {
						result = postResult;
					}
				}
			}
		}
		return result;
	}

	private static Result runJob(Run build, BuildListener listener, String jobName, String jobParametersNotExpanded, String propertiesFileToInject) throws InterruptedException, IOException {
		log.info(build.getFullDisplayName() + " running " + jobName + " before matrix jobs.");

		AbstractProject<?, ? extends AbstractBuild> jobToRun = findJob(jobName);

		if(jobToRun.isDisabled()) {
			throw new IllegalArgumentException(jobToRun.getFullDisplayName() + " has been disabled.");
		}

		listener.getLogger().println("Scheduling " + jobName);

		String jobParametersExpanded = build.getEnvironment(listener).expand(jobParametersNotExpanded);

		QueueTaskFuture<? extends AbstractBuild> future = null;
		for(int i = 0; future == null && i < NUM_RETRIES; i++) {
			ParametersAction parameterValues = parseParameters(jobParametersExpanded, jobToRun.getProperty(ParametersDefinitionProperty.class), listener);
			future = jobToRun.scheduleBuild2(i, new Cause.UpstreamCause(build), parameterValues);
			if (future == null && i < NUM_RETRIES - 1) { //Don't sleep if it's the last one.
				listener.getLogger().println("Couldn't schedule " + jobToRun.getFullDisplayName() + ". Retrying ("+i+").");
				Thread.sleep(5000);
			}
		}

		if(future == null) {
			String errorMessage = build.getFullDisplayName() + " was unable to schedule " + jobName + ".";
			log.warning(errorMessage);
			throw new NullPointerException(errorMessage);
		}

		try {
			AbstractBuild abstractBuild = future.waitForStart();
			if(abstractBuild == null) {
				log.severe("The build's waitForStart future returned null. This is most likely a bug in Jenkins core.");
				throw new NullPointerException("The build's waitForStart future returned null. This is most likely a bug in Jenkins core.");
			}

			listener.getLogger().print("Running ");
			listener.hyperlink("../../../" + abstractBuild.getUrl(), abstractBuild.getFullDisplayName());
			listener.getLogger().println(".");
		} catch (ExecutionException e) {
			log.severe(e.getMessage() + "\n" + ExceptionUtils.getFullStackTrace(e));
			throw new RuntimeException(e);
		}

		try {
			AbstractBuild abstractBuild = future.get();
			if(abstractBuild == null) {
				log.severe("The build's future returned null. This is most likely a bug in Jenkins core.");
				throw new NullPointerException("The build's future returned null. This is most likely a bug in Jenkins core.");
			}

			listener.getLogger().print("Finished running ");
			listener.hyperlink("../../../" + abstractBuild.getUrl(), abstractBuild.getFullDisplayName());
			listener.getLogger().println(".");

			if(propertiesFileToInject != null && !propertiesFileToInject.isEmpty()) {
				listener.getLogger().println("Injecting " + propertiesFileToInject);
				File artifactsDir = abstractBuild.getArtifactsDir();
				File artifact = new File(artifactsDir, propertiesFileToInject);

				FileInputStream inputStream = new FileInputStream(artifact);
				Properties properties;
				try {
					properties = new Properties();
					properties.load(inputStream);
				} finally {
					inputStream.close();
				}

				build.addAction(new MapInjectorAction(properties));
			}
			return abstractBuild.getResult();
		} catch (ExecutionException e) {
			log.severe(e.getMessage() + "\n" + ExceptionUtils.getFullStackTrace(e));
			throw new RuntimeException(e);
		}
	}

	/**
	 * Searches for a project with the given name or throws an exception if the given project doesn't exist.
	 * @param jobName The name of the job to search for.
	 * @return The first project that matches the given name (However, it shouldn't be possible to have a conflict.)
	 */
	private static AbstractProject<?, ? extends AbstractBuild> findJob(String jobName) {
		AbstractProject<?, ? extends AbstractBuild> jobToRun = null;
		List<AbstractProject> allItems = Jenkins.getInstance().getAllItems(AbstractProject.class);
		for (AbstractProject allItem : allItems) {
			if (allItem.getName().equals(jobName)) {
				//noinspection unchecked
				jobToRun = allItem;
				break;
			}
		}

		if (jobToRun == null) {
			throw new IllegalArgumentException("The specified Job name (" + jobName + ") does not exist. Failing.");
		}
		return jobToRun;
	}

	private static ParametersAction parseParameters(String propertiesStr, ParametersDefinitionProperty parameterDefinitions, BuildListener listener) {
		StringInputStream inStream = new StringInputStream(propertiesStr);
		return parseParameters(inStream, parameterDefinitions, listener);
	}

	private static ParametersAction parseParameters(InputStream inStream, ParametersDefinitionProperty parameterDefinitions, BuildListener listener) {
		List<ParameterValue> values = new LinkedList<ParameterValue>();

		Properties properties = new Properties();
		try {
			properties.load(inStream);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		listener.getLogger().println("Using parameters: ");
		Set<String> addedParameters = new HashSet<String>();
		for (Object keyObj : properties.keySet()) {
			String key = String.valueOf(keyObj);
			String value = properties.getProperty(key);
			listener.getLogger().println("\t`"+key+"`=>`"+value+"`");
			values.add(new StringParameterValue(key, value));
			addedParameters.add(key);
		}

		if(parameterDefinitions != null) {
			for (String defaultParameterName : parameterDefinitions.getParameterDefinitionNames()) {
				if(!addedParameters.contains(defaultParameterName)) {
					ParameterDefinition parameterDefinition = parameterDefinitions.getParameterDefinition(defaultParameterName);
					ParameterValue defaultParameterValue = parameterDefinition.getDefaultParameterValue();
					listener.getLogger().println("\t`Using default: `"+defaultParameterName+"`");
					values.add(defaultParameterValue);
				}
			}
		}

		return new ParametersAction(values);
	}

	private Result preRun(Run build, BuildListener listener) throws InterruptedException, IOException {
		return runJob(build, listener, getPreJobName(), getPreJobParameters(), getPrePropertiesFileToInject());
	}

	private Result postRun(Run build, BuildListener listener) throws InterruptedException, IOException {
		return runJob(build, listener, getPostJobName(), getPostJobParameters(), getPostPropertiesFileToInject());
	}

	public String getPreJobName() {
		return preJobName;
	}

	public String getPreJobParameters() {
		return preJobParameters;
	}

	public String getPrePropertiesFileToInject() {
		return prePropertiesFileToInject;
	}

	public String getPostJobName() {
		return postJobName;
	}

	public String getPostJobParameters() {
		return postJobParameters;
	}

	public String getPostPropertiesFileToInject() {
		return postPropertiesFileToInject;
	}

	@Extension
	public static class DescriptorImpl extends MatrixExecutionStrategyDescriptor {
		@Override
		public String getDisplayName() {
			return "Trigger Job Before/After Matrix Builds";
		}
	}
}
