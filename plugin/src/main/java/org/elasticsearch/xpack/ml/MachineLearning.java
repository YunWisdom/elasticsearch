/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.NamedDiff;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.monitor.os.OsProbe;
import org.elasticsearch.monitor.os.OsStats;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ExecutorBuilder;
import org.elasticsearch.threadpool.FixedExecutorBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.ml.action.CloseJobAction;
import org.elasticsearch.xpack.ml.action.DeleteDatafeedAction;
import org.elasticsearch.xpack.ml.action.DeleteExpiredDataAction;
import org.elasticsearch.xpack.ml.action.DeleteFilterAction;
import org.elasticsearch.xpack.ml.action.DeleteJobAction;
import org.elasticsearch.xpack.ml.action.DeleteModelSnapshotAction;
import org.elasticsearch.xpack.ml.action.FinalizeJobExecutionAction;
import org.elasticsearch.xpack.ml.action.FlushJobAction;
import org.elasticsearch.xpack.ml.action.ForecastJobAction;
import org.elasticsearch.xpack.ml.action.GetBucketsAction;
import org.elasticsearch.xpack.ml.action.GetCategoriesAction;
import org.elasticsearch.xpack.ml.action.GetDatafeedsAction;
import org.elasticsearch.xpack.ml.action.GetDatafeedsStatsAction;
import org.elasticsearch.xpack.ml.action.GetFiltersAction;
import org.elasticsearch.xpack.ml.action.GetInfluencersAction;
import org.elasticsearch.xpack.ml.action.GetJobsAction;
import org.elasticsearch.xpack.ml.action.GetJobsStatsAction;
import org.elasticsearch.xpack.ml.action.GetModelSnapshotsAction;
import org.elasticsearch.xpack.ml.action.GetOverallBucketsAction;
import org.elasticsearch.xpack.ml.action.GetRecordsAction;
import org.elasticsearch.xpack.ml.action.IsolateDatafeedAction;
import org.elasticsearch.xpack.ml.action.KillProcessAction;
import org.elasticsearch.xpack.ml.action.OpenJobAction;
import org.elasticsearch.xpack.ml.action.PostDataAction;
import org.elasticsearch.xpack.ml.action.PreviewDatafeedAction;
import org.elasticsearch.xpack.ml.action.PutDatafeedAction;
import org.elasticsearch.xpack.ml.action.PutFilterAction;
import org.elasticsearch.xpack.ml.action.PutJobAction;
import org.elasticsearch.xpack.ml.action.RevertModelSnapshotAction;
import org.elasticsearch.xpack.ml.action.StartDatafeedAction;
import org.elasticsearch.xpack.ml.action.StopDatafeedAction;
import org.elasticsearch.xpack.ml.action.UpdateDatafeedAction;
import org.elasticsearch.xpack.ml.action.UpdateJobAction;
import org.elasticsearch.xpack.ml.action.UpdateModelSnapshotAction;
import org.elasticsearch.xpack.ml.action.UpdateProcessAction;
import org.elasticsearch.xpack.ml.action.ValidateDetectorAction;
import org.elasticsearch.xpack.ml.action.ValidateJobConfigAction;
import org.elasticsearch.xpack.ml.datafeed.DatafeedJobBuilder;
import org.elasticsearch.xpack.ml.datafeed.DatafeedManager;
import org.elasticsearch.xpack.ml.datafeed.DatafeedState;
import org.elasticsearch.xpack.ml.job.JobManager;
import org.elasticsearch.xpack.ml.job.UpdateJobProcessNotifier;
import org.elasticsearch.xpack.ml.job.config.JobTaskStatus;
import org.elasticsearch.xpack.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.ml.job.persistence.ElasticsearchMappings;
import org.elasticsearch.xpack.ml.job.persistence.JobDataCountsPersister;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsPersister;
import org.elasticsearch.xpack.ml.job.process.DataCountsReporter;
import org.elasticsearch.xpack.ml.job.process.NativeController;
import org.elasticsearch.xpack.ml.job.process.NativeControllerHolder;
import org.elasticsearch.xpack.ml.job.process.ProcessCtrl;
import org.elasticsearch.xpack.ml.job.process.autodetect.AutodetectProcessFactory;
import org.elasticsearch.xpack.ml.job.process.autodetect.AutodetectProcessManager;
import org.elasticsearch.xpack.ml.job.process.autodetect.BlackHoleAutodetectProcess;
import org.elasticsearch.xpack.ml.job.process.autodetect.NativeAutodetectProcessFactory;
import org.elasticsearch.xpack.ml.job.process.normalizer.MultiplyingNormalizerProcess;
import org.elasticsearch.xpack.ml.job.process.normalizer.NativeNormalizerProcessFactory;
import org.elasticsearch.xpack.ml.job.process.normalizer.NormalizerFactory;
import org.elasticsearch.xpack.ml.job.process.normalizer.NormalizerProcessFactory;
import org.elasticsearch.xpack.ml.notifications.AuditMessage;
import org.elasticsearch.xpack.ml.notifications.Auditor;
import org.elasticsearch.xpack.ml.rest.RestDeleteExpiredDataAction;
import org.elasticsearch.xpack.ml.rest.datafeeds.RestDeleteDatafeedAction;
import org.elasticsearch.xpack.ml.rest.datafeeds.RestGetDatafeedStatsAction;
import org.elasticsearch.xpack.ml.rest.datafeeds.RestGetDatafeedsAction;
import org.elasticsearch.xpack.ml.rest.datafeeds.RestPreviewDatafeedAction;
import org.elasticsearch.xpack.ml.rest.datafeeds.RestPutDatafeedAction;
import org.elasticsearch.xpack.ml.rest.datafeeds.RestStartDatafeedAction;
import org.elasticsearch.xpack.ml.rest.datafeeds.RestStopDatafeedAction;
import org.elasticsearch.xpack.ml.rest.datafeeds.RestUpdateDatafeedAction;
import org.elasticsearch.xpack.ml.rest.filter.RestDeleteFilterAction;
import org.elasticsearch.xpack.ml.rest.filter.RestGetFiltersAction;
import org.elasticsearch.xpack.ml.rest.filter.RestPutFilterAction;
import org.elasticsearch.xpack.ml.rest.job.RestCloseJobAction;
import org.elasticsearch.xpack.ml.rest.job.RestDeleteJobAction;
import org.elasticsearch.xpack.ml.rest.job.RestFlushJobAction;
import org.elasticsearch.xpack.ml.rest.job.RestForecastJobAction;
import org.elasticsearch.xpack.ml.rest.job.RestGetJobStatsAction;
import org.elasticsearch.xpack.ml.rest.job.RestGetJobsAction;
import org.elasticsearch.xpack.ml.rest.job.RestOpenJobAction;
import org.elasticsearch.xpack.ml.rest.job.RestPostDataAction;
import org.elasticsearch.xpack.ml.rest.job.RestPostJobUpdateAction;
import org.elasticsearch.xpack.ml.rest.job.RestPutJobAction;
import org.elasticsearch.xpack.ml.rest.modelsnapshots.RestDeleteModelSnapshotAction;
import org.elasticsearch.xpack.ml.rest.modelsnapshots.RestGetModelSnapshotsAction;
import org.elasticsearch.xpack.ml.rest.modelsnapshots.RestRevertModelSnapshotAction;
import org.elasticsearch.xpack.ml.rest.modelsnapshots.RestUpdateModelSnapshotAction;
import org.elasticsearch.xpack.ml.rest.results.RestGetBucketsAction;
import org.elasticsearch.xpack.ml.rest.results.RestGetCategoriesAction;
import org.elasticsearch.xpack.ml.rest.results.RestGetInfluencersAction;
import org.elasticsearch.xpack.ml.rest.results.RestGetOverallBucketsAction;
import org.elasticsearch.xpack.ml.rest.results.RestGetRecordsAction;
import org.elasticsearch.xpack.ml.rest.validate.RestValidateDetectorAction;
import org.elasticsearch.xpack.ml.rest.validate.RestValidateJobConfigAction;
import org.elasticsearch.xpack.persistent.PersistentTaskParams;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.xpack.persistent.PersistentTasksExecutor;
import org.elasticsearch.xpack.persistent.PersistentTasksNodeService;
import org.elasticsearch.xpack.persistent.PersistentTasksService;
import org.elasticsearch.xpack.template.TemplateUtils;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static java.util.Collections.emptyList;

public class MachineLearning implements ActionPlugin {
    public static final String NAME = "ml";
    public static final String BASE_PATH = "/_xpack/ml/";
    public static final String DATAFEED_THREAD_POOL_NAME = NAME + "_datafeed";
    public static final String AUTODETECT_THREAD_POOL_NAME = NAME + "_autodetect";
    public static final String UTILITY_THREAD_POOL_NAME = NAME + "_utility";

    public static final Setting<Boolean> AUTODETECT_PROCESS =
            Setting.boolSetting("xpack.ml.autodetect_process", true, Property.NodeScope);
    public static final Setting<Boolean> ML_ENABLED =
            Setting.boolSetting("node.ml", XPackSettings.MACHINE_LEARNING_ENABLED, Property.NodeScope);
    public static final String ML_ENABLED_NODE_ATTR = "ml.enabled";
    public static final String MAX_OPEN_JOBS_NODE_ATTR = "ml.max_open_jobs";
    public static final String MACHINE_MEMORY_NODE_ATTR = "ml.machine_memory";
    public static final Setting<Integer> CONCURRENT_JOB_ALLOCATIONS =
            Setting.intSetting("xpack.ml.node_concurrent_job_allocations", 2, 0, Property.Dynamic, Property.NodeScope);
    public static final Setting<ByteSizeValue> MAX_MODEL_MEMORY_LIMIT =
            Setting.memorySizeSetting("xpack.ml.max_model_memory_limit", new ByteSizeValue(0), Property.Dynamic, Property.NodeScope);
    public static final Setting<Integer> MAX_MACHINE_MEMORY_PERCENT =
            Setting.intSetting("xpack.ml.max_machine_memory_percent", 30, 5, 90, Property.Dynamic, Property.NodeScope);

    public static final TimeValue STATE_PERSIST_RESTORE_TIMEOUT = TimeValue.timeValueMinutes(30);

    private static final Logger logger = Loggers.getLogger(XPackPlugin.class);

    private final Settings settings;
    private final Environment env;
    private final XPackLicenseState licenseState;
    private final boolean enabled;
    private final boolean transportClientMode;
    private final boolean tribeNode;
    private final boolean tribeNodeClient;

    private final SetOnce<AutodetectProcessManager> autodetectProcessManager = new SetOnce<>();
    private final SetOnce<DatafeedManager> datafeedManager = new SetOnce<>();

    public MachineLearning(Settings settings, Environment env, XPackLicenseState licenseState) {
        this.settings = settings;
        this.env = env;
        this.licenseState = licenseState;
        this.enabled = XPackSettings.MACHINE_LEARNING_ENABLED.get(settings);
        this.transportClientMode = XPackPlugin.transportClientMode(settings);
        this.tribeNode = XPackPlugin.isTribeNode(settings);
        this.tribeNodeClient = XPackPlugin.isTribeClientNode(settings);
    }

    public List<Setting<?>> getSettings() {
        return Collections.unmodifiableList(
                Arrays.asList(AUTODETECT_PROCESS,
                        ML_ENABLED,
                        CONCURRENT_JOB_ALLOCATIONS,
                        MAX_MODEL_MEMORY_LIMIT,
                        MAX_MACHINE_MEMORY_PERCENT,
                        ProcessCtrl.DONT_PERSIST_MODEL_STATE_SETTING,
                        ProcessCtrl.MAX_ANOMALY_RECORDS_SETTING,
                        DataCountsReporter.ACCEPTABLE_PERCENTAGE_DATE_PARSE_ERRORS_SETTING,
                        DataCountsReporter.ACCEPTABLE_PERCENTAGE_OUT_OF_ORDER_ERRORS_SETTING,
                        AutodetectProcessManager.MAX_RUNNING_JOBS_PER_NODE,
                        AutodetectProcessManager.MAX_OPEN_JOBS_PER_NODE));
    }

    public Settings additionalSettings() {
        String mlEnabledNodeAttrName = "node.attr." + ML_ENABLED_NODE_ATTR;
        String maxOpenJobsPerNodeNodeAttrName = "node.attr." + MAX_OPEN_JOBS_NODE_ATTR;
        String machineMemoryAttrName = "node.attr." + MACHINE_MEMORY_NODE_ATTR;

        if (enabled == false || transportClientMode || tribeNode || tribeNodeClient) {
            disallowMlNodeAttributes(mlEnabledNodeAttrName, maxOpenJobsPerNodeNodeAttrName, machineMemoryAttrName);
            return Settings.EMPTY;
        }

        Settings.Builder additionalSettings = Settings.builder();
        Boolean allocationEnabled = ML_ENABLED.get(settings);
        if (allocationEnabled != null && allocationEnabled) {
            // TODO: the simple true/false flag will not be required once all supported versions have the number - consider removing in 7.0
            addMlNodeAttribute(additionalSettings, mlEnabledNodeAttrName, "true");
            addMlNodeAttribute(additionalSettings, maxOpenJobsPerNodeNodeAttrName,
                    String.valueOf(AutodetectProcessManager.MAX_OPEN_JOBS_PER_NODE.get(settings)));
            addMlNodeAttribute(additionalSettings, machineMemoryAttrName,
                    Long.toString(machineMemoryFromStats(OsProbe.getInstance().osStats())));
        } else {
            disallowMlNodeAttributes(mlEnabledNodeAttrName, maxOpenJobsPerNodeNodeAttrName, machineMemoryAttrName);
        }
        return additionalSettings.build();
    }

    private void addMlNodeAttribute(Settings.Builder additionalSettings, String attrName, String value) {
        // Unfortunately we cannot simply disallow any value, because the internal cluster integration
        // test framework will restart nodes with settings copied from the node immediately before it
        // was stopped.  The best we can do is reject inconsistencies, and report this in a way that
        // makes clear that setting the node attribute directly is not allowed.
        String oldValue = settings.get(attrName);
        if (oldValue == null || oldValue.equals(value)) {
            additionalSettings.put(attrName, value);
        } else {
            reportClashingNodeAttribute(attrName);
        }
    }

    private void disallowMlNodeAttributes(String... mlNodeAttributes) {
        for (String attrName : mlNodeAttributes) {
            if (settings.get(attrName) != null) {
                reportClashingNodeAttribute(attrName);
            }
        }
    }

    private void reportClashingNodeAttribute(String attrName) {
        throw new IllegalArgumentException("Directly setting [" + attrName + "] is not permitted - " +
                "it is reserved for machine learning. If your intention was to customize machine learning, set the [" +
                attrName.replace("node.attr.", "xpack.") + "] setting instead.");
    }

    public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
        return Arrays.asList(
                // Custom metadata
                new NamedWriteableRegistry.Entry(MetaData.Custom.class, "ml", MlMetadata::new),
                new NamedWriteableRegistry.Entry(NamedDiff.class, "ml", MlMetadata.MlMetadataDiff::new),
                new NamedWriteableRegistry.Entry(MetaData.Custom.class, PersistentTasksCustomMetaData.TYPE,
                        PersistentTasksCustomMetaData::new),
                new NamedWriteableRegistry.Entry(NamedDiff.class, PersistentTasksCustomMetaData.TYPE,
                        PersistentTasksCustomMetaData::readDiffFrom),

                // Persistent action requests
                new NamedWriteableRegistry.Entry(PersistentTaskParams.class, StartDatafeedAction.TASK_NAME,
                        StartDatafeedAction.DatafeedParams::new),
                new NamedWriteableRegistry.Entry(PersistentTaskParams.class, OpenJobAction.TASK_NAME,
                        OpenJobAction.JobParams::new),

                // Task statuses
                new NamedWriteableRegistry.Entry(Task.Status.class, PersistentTasksNodeService.Status.NAME,
                        PersistentTasksNodeService.Status::new),
                new NamedWriteableRegistry.Entry(Task.Status.class, JobTaskStatus.NAME, JobTaskStatus::new),
                new NamedWriteableRegistry.Entry(Task.Status.class, DatafeedState.NAME, DatafeedState::fromStream)
        );
    }

    public List<NamedXContentRegistry.Entry> getNamedXContent() {
        return Arrays.asList(
                // Custom metadata
                new NamedXContentRegistry.Entry(MetaData.Custom.class, new ParseField("ml"),
                        parser -> MlMetadata.METADATA_PARSER.parse(parser, null).build()),
                new NamedXContentRegistry.Entry(MetaData.Custom.class, new ParseField(PersistentTasksCustomMetaData.TYPE),
                        PersistentTasksCustomMetaData::fromXContent),

                // Persistent action requests
                new NamedXContentRegistry.Entry(PersistentTaskParams.class, new ParseField(StartDatafeedAction.TASK_NAME),
                        StartDatafeedAction.DatafeedParams::fromXContent),
                new NamedXContentRegistry.Entry(PersistentTaskParams.class, new ParseField(OpenJobAction.TASK_NAME),
                        OpenJobAction.JobParams::fromXContent),

                // Task statuses
                new NamedXContentRegistry.Entry(Task.Status.class, new ParseField(DatafeedState.NAME), DatafeedState::fromXContent),
                new NamedXContentRegistry.Entry(Task.Status.class, new ParseField(JobTaskStatus.NAME), JobTaskStatus::fromXContent)
        );
    }

    public Collection<Object> createComponents(Client client, ClusterService clusterService, ThreadPool threadPool,
                                               NamedXContentRegistry xContentRegistry, PersistentTasksService persistentTasksService) {
        if (enabled == false || transportClientMode || tribeNode || tribeNodeClient) {
            return emptyList();
        }

        Auditor auditor = new Auditor(client, clusterService);
        JobProvider jobProvider = new JobProvider(client, settings);
        UpdateJobProcessNotifier notifier = new UpdateJobProcessNotifier(settings, client, clusterService, threadPool);
        JobManager jobManager = new JobManager(settings, jobProvider, clusterService, auditor, client, notifier);

        JobDataCountsPersister jobDataCountsPersister = new JobDataCountsPersister(settings, client);
        JobResultsPersister jobResultsPersister = new JobResultsPersister(settings, client);

        AutodetectProcessFactory autodetectProcessFactory;
        NormalizerProcessFactory normalizerProcessFactory;
        if (AUTODETECT_PROCESS.get(settings) && MachineLearningFeatureSet.isRunningOnMlPlatform(true)) {
            try {
                NativeController nativeController = NativeControllerHolder.getNativeController(env);
                if (nativeController == null) {
                    // This will only only happen when path.home is not set, which is disallowed in production
                    throw new ElasticsearchException("Failed to create native process controller for Machine Learning");
                }
                autodetectProcessFactory = new NativeAutodetectProcessFactory(env, settings, nativeController, client);
                normalizerProcessFactory = new NativeNormalizerProcessFactory(env, settings, nativeController);
            } catch (IOException e) {
                // This also should not happen in production, as the MachineLearningFeatureSet should have
                // hit the same error first and brought down the node with a friendlier error message
                throw new ElasticsearchException("Failed to create native process factories for Machine Learning", e);
            }
        } else {
            autodetectProcessFactory = (job, modelSnapshot, quantiles, filters, executorService, onProcessCrash) ->
                    new BlackHoleAutodetectProcess(job.getId());
            // factor of 1.0 makes renormalization a no-op
            normalizerProcessFactory = (jobId, quantilesState, bucketSpan, perPartitionNormalization,
                                        executorService) -> new MultiplyingNormalizerProcess(settings, 1.0);
        }
        NormalizerFactory normalizerFactory = new NormalizerFactory(normalizerProcessFactory,
                threadPool.executor(MachineLearning.UTILITY_THREAD_POOL_NAME));
        AutodetectProcessManager autodetectProcessManager = new AutodetectProcessManager(settings, client, threadPool,
                jobManager, jobProvider, jobResultsPersister, jobDataCountsPersister, autodetectProcessFactory,
                normalizerFactory, xContentRegistry, auditor);
        this.autodetectProcessManager.set(autodetectProcessManager);
        DatafeedJobBuilder datafeedJobBuilder = new DatafeedJobBuilder(client, jobProvider, auditor, System::currentTimeMillis);
        DatafeedManager datafeedManager = new DatafeedManager(threadPool, client, clusterService, datafeedJobBuilder,
                System::currentTimeMillis, auditor, persistentTasksService);
        this.datafeedManager.set(datafeedManager);
        MlLifeCycleService mlLifeCycleService = new MlLifeCycleService(env, clusterService, datafeedManager, autodetectProcessManager);
        InvalidLicenseEnforcer invalidLicenseEnforcer =
                new InvalidLicenseEnforcer(settings, licenseState, threadPool, datafeedManager, autodetectProcessManager);

        return Arrays.asList(
                mlLifeCycleService,
                jobProvider,
                jobManager,
                autodetectProcessManager,
                new MlInitializationService(settings, threadPool, clusterService, client),
                jobDataCountsPersister,
                datafeedManager,
                auditor,
                invalidLicenseEnforcer,
                new MlAssignmentNotifier(settings, auditor, clusterService)
        );
    }

    public List<PersistentTasksExecutor<?>> createPersistentTasksExecutors(ClusterService clusterService) {
        if (enabled == false || transportClientMode || tribeNode || tribeNodeClient) {
            return emptyList();
        }

        return Arrays.asList(
                new OpenJobAction.OpenJobPersistentTasksExecutor(settings, clusterService, autodetectProcessManager.get()),
                new StartDatafeedAction.StartDatafeedPersistentTasksExecutor(settings, datafeedManager.get())
        );
    }

    public Collection<Module> nodeModules() {
        List<Module> modules = new ArrayList<>();

        if (tribeNodeClient || transportClientMode) {
            return modules;
        }

        modules.add(b -> {
            XPackPlugin.bindFeatureSet(b, MachineLearningFeatureSet.class);
        });

        return modules;
    }

    @Override
    public List<RestHandler> getRestHandlers(Settings settings, RestController restController, ClusterSettings clusterSettings,
                                             IndexScopedSettings indexScopedSettings, SettingsFilter settingsFilter,
                                             IndexNameExpressionResolver indexNameExpressionResolver,
                                             Supplier<DiscoveryNodes> nodesInCluster) {
        if (false == enabled || tribeNodeClient || tribeNode) {
            return emptyList();
        }
        return Arrays.asList(
            new RestGetJobsAction(settings, restController),
            new RestGetJobStatsAction(settings, restController),
            new RestPutJobAction(settings, restController),
            new RestPostJobUpdateAction(settings, restController),
            new RestDeleteJobAction(settings, restController),
            new RestOpenJobAction(settings, restController),
            new RestGetFiltersAction(settings, restController),
            new RestPutFilterAction(settings, restController),
            new RestDeleteFilterAction(settings, restController),
            new RestGetInfluencersAction(settings, restController),
            new RestGetRecordsAction(settings, restController),
            new RestGetBucketsAction(settings, restController),
            new RestGetOverallBucketsAction(settings, restController),
            new RestPostDataAction(settings, restController),
            new RestCloseJobAction(settings, restController),
            new RestFlushJobAction(settings, restController),
            new RestValidateDetectorAction(settings, restController),
            new RestValidateJobConfigAction(settings, restController),
            new RestGetCategoriesAction(settings, restController),
            new RestGetModelSnapshotsAction(settings, restController),
            new RestRevertModelSnapshotAction(settings, restController),
            new RestUpdateModelSnapshotAction(settings, restController),
            new RestGetDatafeedsAction(settings, restController),
            new RestGetDatafeedStatsAction(settings, restController),
            new RestPutDatafeedAction(settings, restController),
            new RestUpdateDatafeedAction(settings, restController),
            new RestDeleteDatafeedAction(settings, restController),
            new RestPreviewDatafeedAction(settings, restController),
            new RestStartDatafeedAction(settings, restController),
            new RestStopDatafeedAction(settings, restController),
            new RestDeleteModelSnapshotAction(settings, restController),
            new RestDeleteExpiredDataAction(settings, restController), 
            new RestForecastJobAction(settings, restController)
        );
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        if (false == enabled || tribeNodeClient || tribeNode) {
            return emptyList();
        }
        return Arrays.asList(
                new ActionHandler<>(GetJobsAction.INSTANCE, GetJobsAction.TransportAction.class),
                new ActionHandler<>(GetJobsStatsAction.INSTANCE, GetJobsStatsAction.TransportAction.class),
                new ActionHandler<>(PutJobAction.INSTANCE, PutJobAction.TransportAction.class),
                new ActionHandler<>(UpdateJobAction.INSTANCE, UpdateJobAction.TransportAction.class),
                new ActionHandler<>(DeleteJobAction.INSTANCE, DeleteJobAction.TransportAction.class),
                new ActionHandler<>(OpenJobAction.INSTANCE, OpenJobAction.TransportAction.class),
                new ActionHandler<>(GetFiltersAction.INSTANCE, GetFiltersAction.TransportAction.class),
                new ActionHandler<>(PutFilterAction.INSTANCE, PutFilterAction.TransportAction.class),
                new ActionHandler<>(DeleteFilterAction.INSTANCE, DeleteFilterAction.TransportAction.class),
                new ActionHandler<>(KillProcessAction.INSTANCE, KillProcessAction.TransportAction.class),
                new ActionHandler<>(GetBucketsAction.INSTANCE, GetBucketsAction.TransportAction.class),
                new ActionHandler<>(GetInfluencersAction.INSTANCE, GetInfluencersAction.TransportAction.class),
                new ActionHandler<>(GetOverallBucketsAction.INSTANCE, GetOverallBucketsAction.TransportAction.class),
                new ActionHandler<>(GetRecordsAction.INSTANCE, GetRecordsAction.TransportAction.class),
                new ActionHandler<>(PostDataAction.INSTANCE, PostDataAction.TransportAction.class),
                new ActionHandler<>(CloseJobAction.INSTANCE, CloseJobAction.TransportAction.class),
                new ActionHandler<>(FinalizeJobExecutionAction.INSTANCE, FinalizeJobExecutionAction.TransportAction.class),
                new ActionHandler<>(FlushJobAction.INSTANCE, FlushJobAction.TransportAction.class),
                new ActionHandler<>(ValidateDetectorAction.INSTANCE, ValidateDetectorAction.TransportAction.class),
                new ActionHandler<>(ValidateJobConfigAction.INSTANCE, ValidateJobConfigAction.TransportAction.class),
                new ActionHandler<>(GetCategoriesAction.INSTANCE, GetCategoriesAction.TransportAction.class),
                new ActionHandler<>(GetModelSnapshotsAction.INSTANCE, GetModelSnapshotsAction.TransportAction.class),
                new ActionHandler<>(RevertModelSnapshotAction.INSTANCE, RevertModelSnapshotAction.TransportAction.class),
                new ActionHandler<>(UpdateModelSnapshotAction.INSTANCE, UpdateModelSnapshotAction.TransportAction.class),
                new ActionHandler<>(GetDatafeedsAction.INSTANCE, GetDatafeedsAction.TransportAction.class),
                new ActionHandler<>(GetDatafeedsStatsAction.INSTANCE, GetDatafeedsStatsAction.TransportAction.class),
                new ActionHandler<>(PutDatafeedAction.INSTANCE, PutDatafeedAction.TransportAction.class),
                new ActionHandler<>(UpdateDatafeedAction.INSTANCE, UpdateDatafeedAction.TransportAction.class),
                new ActionHandler<>(DeleteDatafeedAction.INSTANCE, DeleteDatafeedAction.TransportAction.class),
                new ActionHandler<>(PreviewDatafeedAction.INSTANCE, PreviewDatafeedAction.TransportAction.class),
                new ActionHandler<>(StartDatafeedAction.INSTANCE, StartDatafeedAction.TransportAction.class),
                new ActionHandler<>(StopDatafeedAction.INSTANCE, StopDatafeedAction.TransportAction.class),
                new ActionHandler<>(IsolateDatafeedAction.INSTANCE, IsolateDatafeedAction.TransportAction.class),
                new ActionHandler<>(DeleteModelSnapshotAction.INSTANCE, DeleteModelSnapshotAction.TransportAction.class),
                new ActionHandler<>(UpdateProcessAction.INSTANCE, UpdateProcessAction.TransportAction.class),
                new ActionHandler<>(DeleteExpiredDataAction.INSTANCE, DeleteExpiredDataAction.TransportAction.class),
                new ActionHandler<>(ForecastJobAction.INSTANCE, ForecastJobAction.TransportAction.class)
        );
    }

    public List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
        if (false == enabled || tribeNode || tribeNodeClient || transportClientMode) {
            return emptyList();
        }
        int maxNumberOfJobs = AutodetectProcessManager.MAX_OPEN_JOBS_PER_NODE.get(settings);
        // 4 threads per job: for cpp logging, result processing, state processing and
        // AutodetectProcessManager worker thread:
        FixedExecutorBuilder autoDetect = new FixedExecutorBuilder(settings, AUTODETECT_THREAD_POOL_NAME,
                maxNumberOfJobs * 4, 4, "xpack.ml.autodetect_thread_pool");

        // 4 threads per job: processing logging, result and state of the renormalization process.
        // Renormalization does't run for the entire lifetime of a job, so additionally autodetect process
        // based operation (open, close, flush, post data), datafeed based operations (start and stop)
        // and deleting expired data use this threadpool too and queue up if all threads are busy.
        FixedExecutorBuilder renormalizer = new FixedExecutorBuilder(settings, UTILITY_THREAD_POOL_NAME,
                maxNumberOfJobs * 4, 500, "xpack.ml.utility_thread_pool");

        // TODO: if datafeed and non datafeed jobs are considered more equal and the datafeed and
        // autodetect process are created at the same time then these two different TPs can merge.
        FixedExecutorBuilder datafeed = new FixedExecutorBuilder(settings, DATAFEED_THREAD_POOL_NAME,
                maxNumberOfJobs, 200, "xpack.ml.datafeed_thread_pool");
        return Arrays.asList(autoDetect, renormalizer, datafeed);
    }

    public UnaryOperator<Map<String, IndexTemplateMetaData>> getIndexTemplateMetaDataUpgrader() {
        return templates -> {
            final TimeValue delayedNodeTimeOutSetting;
            // Whether we are using native process is a good way to detect whether we are in dev / test mode:
            if (MachineLearning.AUTODETECT_PROCESS.get(settings)) {
                delayedNodeTimeOutSetting = UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.get(settings);
            } else {
                delayedNodeTimeOutSetting = TimeValue.timeValueNanos(0);
            }

            try (XContentBuilder auditMapping = ElasticsearchMappings.auditMessageMapping()) {
                IndexTemplateMetaData notificationMessageTemplate = IndexTemplateMetaData.builder(Auditor.NOTIFICATIONS_INDEX)
                        .putMapping(AuditMessage.TYPE.getPreferredName(), auditMapping.string())
                        .patterns(Collections.singletonList(Auditor.NOTIFICATIONS_INDEX))
                        .version(Version.CURRENT.id)
                        .settings(Settings.builder()
                                // Our indexes are small and one shard puts the
                                // least possible burden on Elasticsearch
                                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                                .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), delayedNodeTimeOutSetting))
                        .build();
                templates.put(Auditor.NOTIFICATIONS_INDEX, notificationMessageTemplate);
            } catch (IOException e) {
                logger.warn("Error loading the template for the notification message index", e);
            }

            try (XContentBuilder docMapping = MlMetaIndex.docMapping()) {
                IndexTemplateMetaData metaTemplate = IndexTemplateMetaData.builder(MlMetaIndex.INDEX_NAME)
                        .patterns(Collections.singletonList(MlMetaIndex.INDEX_NAME))
                        .settings(Settings.builder()
                                // Our indexes are small and one shard puts the
                                // least possible burden on Elasticsearch
                                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                                .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), delayedNodeTimeOutSetting))
                        .version(Version.CURRENT.id)
                        .putMapping(MlMetaIndex.TYPE, docMapping.string())
                        .build();
                templates.put(MlMetaIndex.INDEX_NAME, metaTemplate);
            } catch (IOException e) {
                logger.warn("Error loading the template for the " + MlMetaIndex.INDEX_NAME + " index", e);
            }

            try (XContentBuilder stateMapping = ElasticsearchMappings.stateMapping()) {
                IndexTemplateMetaData stateTemplate = IndexTemplateMetaData.builder(AnomalyDetectorsIndex.jobStateIndexName())
                        .patterns(Collections.singletonList(AnomalyDetectorsIndex.jobStateIndexName()))
                        // TODO review these settings
                        .settings(Settings.builder()
                                .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), delayedNodeTimeOutSetting)
                                // Sacrifice durability for performance: in the event of power
                                // failure we can lose the last 5 seconds of changes, but it's
                                // much faster
                                .put(IndexSettings.INDEX_TRANSLOG_DURABILITY_SETTING.getKey(), "async"))
                        .putMapping(ElasticsearchMappings.DOC_TYPE, stateMapping.string())
                        .version(Version.CURRENT.id)
                        .build();
                templates.put(AnomalyDetectorsIndex.jobStateIndexName(), stateTemplate);
            } catch (IOException e) {
                logger.error("Error loading the template for the " + AnomalyDetectorsIndex.jobStateIndexName() + " index", e);
            }

            try (XContentBuilder docMapping = ElasticsearchMappings.docMapping()) {
                IndexTemplateMetaData jobResultsTemplate = IndexTemplateMetaData.builder(AnomalyDetectorsIndex.jobResultsIndexPrefix())
                        .patterns(Collections.singletonList(AnomalyDetectorsIndex.jobResultsIndexPrefix() + "*"))
                        .settings(Settings.builder()
                                .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), delayedNodeTimeOutSetting)
                                // Sacrifice durability for performance: in the event of power
                                // failure we can lose the last 5 seconds of changes, but it's
                                // much faster
                                .put(IndexSettings.INDEX_TRANSLOG_DURABILITY_SETTING.getKey(), "async")
                                // set the default all search field
                                .put(IndexSettings.DEFAULT_FIELD_SETTING.getKey(), ElasticsearchMappings.ALL_FIELD_VALUES))
                        .putMapping(ElasticsearchMappings.DOC_TYPE, docMapping.string())
                        .version(Version.CURRENT.id)
                        .build();
                templates.put(AnomalyDetectorsIndex.jobResultsIndexPrefix(), jobResultsTemplate);
            } catch (IOException e) {
                logger.error("Error loading the template for the " + AnomalyDetectorsIndex.jobResultsIndexPrefix() + " indices", e);
            }

            return templates;
        };
    }

    public static boolean allTemplatesInstalled(ClusterState clusterState) {
        boolean allPresent = true;
        List<String> templateNames = Arrays.asList(Auditor.NOTIFICATIONS_INDEX, MlMetaIndex.INDEX_NAME,
                AnomalyDetectorsIndex.jobStateIndexName(), AnomalyDetectorsIndex.jobResultsIndexPrefix());
        for (String templateName : templateNames) {
            allPresent = allPresent && TemplateUtils.checkTemplateExistsAndVersionIsGTECurrentVersion(templateName, clusterState);
        }

        return allPresent;
    }

    /**
     * Find the memory size (in bytes) of the machine this node is running on.
     * Takes container limits (as used by Docker for example) into account.
     */
    static long machineMemoryFromStats(OsStats stats) {
        long mem = stats.getMem().getTotal().getBytes();
        OsStats.Cgroup cgroup = stats.getCgroup();
        if (cgroup != null) {
            String containerLimitStr = cgroup.getMemoryLimitInBytes();
            if (containerLimitStr != null) {
                BigInteger containerLimit = new BigInteger(containerLimitStr);
                if ((containerLimit.compareTo(BigInteger.valueOf(mem)) < 0 && containerLimit.compareTo(BigInteger.ZERO) > 0)
                        // mem < 0 means the value couldn't be obtained for some reason
                        || (mem < 0 && containerLimit.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) < 0)) {
                    mem = containerLimit.longValue();
                }
            }
        }
        return mem;
    }
}
