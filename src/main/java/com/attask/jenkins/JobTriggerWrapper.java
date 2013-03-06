package com.attask.jenkins;

import hudson.Extension;
import hudson.Launcher;
import hudson.matrix.*;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.Exported;

import java.io.IOException;

/**
 * User: Joel Johnson
 * Date: 3/6/13
 * Time: 3:01 PM
 */
public class JobTriggerWrapper extends BuildWrapper implements MatrixAggregatable {
	private final String preJobName;
	private final String preJobParameters;
	private final String prePropertiesFileToInject;
	private final String postJobName;
	private final String postJobParameters;
	private final String postPropertiesFileToInject;
	private final boolean postFailIfDownstreamFails;

	@DataBoundConstructor
	public JobTriggerWrapper(String preJobName, String preJobParameters, String prePropertiesFileToInject, String postJobName, String postJobParameters, String postPropertiesFileToInject, boolean postFailIfDownstreamFails) {
		this.preJobName = preJobName;
		this.preJobParameters = preJobParameters;
		this.prePropertiesFileToInject = prePropertiesFileToInject;
		this.postJobName = postJobName;
		this.postJobParameters = postJobParameters;
		this.postPropertiesFileToInject = postPropertiesFileToInject;
		this.postFailIfDownstreamFails = postFailIfDownstreamFails;
	}

	public MatrixAggregator createAggregator(MatrixBuild build, Launcher launcher, BuildListener listener) {
		return new MyMatrixAggregator(build, launcher, listener);
	}

	private class MyMatrixAggregator extends MatrixAggregator {
		public MyMatrixAggregator(MatrixBuild build, Launcher launcher, BuildListener listener) {
			super(build, launcher, listener);
		}

		@Override
		public boolean startBuild() throws InterruptedException, IOException {
			Result result = PreMatrixJob.runJob(build, listener, preJobName, preJobParameters, prePropertiesFileToInject);
			return result.isBetterThan(Result.FAILURE);
		}

		@Override
		public boolean endBuild() throws InterruptedException, IOException {
			Result result = PreMatrixJob.runJob(build, listener, postJobName, postJobParameters, postPropertiesFileToInject);
			return !postFailIfDownstreamFails || result.isBetterThan(Result.FAILURE);
		}
	}

	@Override
	public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
		return new NoOpEnvironment();
	}

	@Exported
	public String getPreJobName() {
		return preJobName;
	}

	@Exported
	public String getPreJobParameters() {
		return preJobParameters;
	}

	@Exported
	public String getPrePropertiesFileToInject() {
		return prePropertiesFileToInject;
	}

	@Exported
	public String getPostJobName() {
		return postJobName;
	}

	@Exported
	public String getPostJobParameters() {
		return postJobParameters;
	}

	@Exported
	public String getPostPropertiesFileToInject() {
		return postPropertiesFileToInject;
	}

	@Exported
	public boolean PostFailIfDownstreamFails() {
		return postFailIfDownstreamFails;
	}

	@Extension
	public static class DescriptorImpl extends BuildWrapperDescriptor {

		@Override
		public String getDisplayName() {
			return "Trigger Job Before/After Matrix Builds";
		}
		@Override
		public boolean isApplicable(AbstractProject<?, ?> item) {
			return item instanceof MatrixProject;
		}

	}

	private class NoOpEnvironment extends Environment {
		@Override
		public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
			return true;
		}
	}
}
