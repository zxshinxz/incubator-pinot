package com.linkedin.thirdeye.bootstrap.rollup.phase3;

import static com.linkedin.thirdeye.bootstrap.rollup.phase3.RollupPhaseThreeConstants.ROLLUP_PHASE3_CONFIG_PATH;
import static com.linkedin.thirdeye.bootstrap.rollup.phase3.RollupPhaseThreeConstants.ROLLUP_PHASE3_INPUT_PATH;
import static com.linkedin.thirdeye.bootstrap.rollup.phase3.RollupPhaseThreeConstants.ROLLUP_PHASE3_OUTPUT_PATH;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.avro.generic.GenericRecord;
import org.apache.avro.mapred.AvroKey;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.linkedin.thirdeye.bootstrap.DimensionKey;
import com.linkedin.thirdeye.bootstrap.MetricSchema;
import com.linkedin.thirdeye.bootstrap.MetricTimeSeries;
import com.linkedin.thirdeye.bootstrap.MetricType;
import com.linkedin.thirdeye.bootstrap.rollup.AverageBasedRollupFunction;
import com.linkedin.thirdeye.bootstrap.rollup.RollupThresholdFunc;
import com.linkedin.thirdeye.bootstrap.rollup.TotalAggregateBasedRollupFunction;
import com.linkedin.thirdeye.bootstrap.rollup.phase2.RollupPhaseTwoMapOutput;
import com.linkedin.thirdeye.bootstrap.rollup.phase2.RollupPhaseTwoReduceOutput;

public class RollupPhaseThreeJob extends Configured {
  private static final Logger LOG = LoggerFactory
      .getLogger(RollupPhaseThreeJob.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private String name;
  private Properties props;

  public RollupPhaseThreeJob(String name, Properties props) {
    super(new Configuration());
    this.name = name;
    this.props = props;
  }

  public static class RollupPhaseThreeMapper extends
      Mapper<BytesWritable, BytesWritable, BytesWritable, BytesWritable> {
    private RollupPhaseThreeConfig config;
    private List<String> dimensionNames;
    private List<String> metricNames;
    private List<MetricType> metricTypes;
    private MetricSchema metricSchema;
    private List<String> rollupOrder;
    RollupThresholdFunc thresholdFunc;
    MultipleOutputs<BytesWritable, BytesWritable> mos;
    Map<String, Integer> dimensionNameToIndexMapping;

    @Override
    public void setup(Context context) throws IOException, InterruptedException {
      LOG.info("RollupPhaseOneJob.RollupPhaseOneMapper.setup()");
      mos = new MultipleOutputs<BytesWritable, BytesWritable>(context);
      Configuration configuration = context.getConfiguration();
      FileSystem fileSystem = FileSystem.get(configuration);
      Path configPath = new Path(configuration.get(ROLLUP_PHASE3_CONFIG_PATH
          .toString()));
      try {
        config = OBJECT_MAPPER.readValue(fileSystem.open(configPath),
            RollupPhaseThreeConfig.class);
        dimensionNames = config.getDimensionNames();
        dimensionNameToIndexMapping = new HashMap<String, Integer>();

        for (int i = 0; i < dimensionNames.size(); i++) {
          dimensionNameToIndexMapping.put(dimensionNames.get(i), i);
        }
        metricNames = config.getMetricNames();
        metricTypes = Lists.newArrayList();
        for (String type : config.getMetricTypes()) {
          metricTypes.add(MetricType.valueOf(type));
        }
        metricSchema = new MetricSchema(config.getMetricNames(), metricTypes);
        // TODO: get this form config
        thresholdFunc = new TotalAggregateBasedRollupFunction(
            "numberOfMemberConnectionsSent", 5000);
        rollupOrder = config.getRollupOrder();
      } catch (Exception e) {
        throw new IOException(e);
      }
    }

    @Override
    public void map(BytesWritable rawDimensionKeyWritable,
        BytesWritable rollupReduceOutputWritable, Context context)
        throws IOException, InterruptedException {
      // pass through, in the reduce we gather all possible roll up for a given
      // rawDimensionKey
      context.write(rawDimensionKeyWritable, rollupReduceOutputWritable);
    }

    @Override
    public void cleanup(Context context) throws IOException,
        InterruptedException {
      mos.close();
    }

  }

  public static class RollupPhaseThreeReducer extends
      Reducer<BytesWritable, BytesWritable, BytesWritable, BytesWritable> {
    private RollupPhaseThreeConfig config;
    private List<String> dimensionNames;
    private List<String> metricNames;
    private List<MetricType> metricTypes;
    private MetricSchema metricSchema;
    private RollupFunction rollupFunc;
    private RollupThresholdFunc rollupThresholdFunc;
    @Override
    public void setup(Context context) throws IOException, InterruptedException {
      Configuration configuration = context.getConfiguration();
      FileSystem fileSystem = FileSystem.get(configuration);
      Path configPath = new Path(configuration.get(ROLLUP_PHASE3_CONFIG_PATH
          .toString()));
      try {
        config = OBJECT_MAPPER.readValue(fileSystem.open(configPath),
            RollupPhaseThreeConfig.class);
        dimensionNames = config.getDimensionNames();
        metricNames = config.getMetricNames();
        metricTypes = Lists.newArrayList();
        for (String type : config.getMetricTypes()) {
          metricTypes.add(MetricType.valueOf(type));
        }
        metricSchema = new MetricSchema(config.getMetricNames(), metricTypes);
        rollupFunc = new DefaultRollupFunc();
        rollupThresholdFunc = new TotalAggregateBasedRollupFunction(
            "numberOfMemberConnectionsSent", 5000);
      } catch (Exception e) {
        throw new IOException(e);
      }
    }

    @Override
    public void reduce(BytesWritable rawDimensionKeyWritable,
        Iterable<BytesWritable> rollupReduceOutputWritableIterable,
        Context context) throws IOException, InterruptedException {
      DimensionKey rawDimensionKey = DimensionKey.fromBytes(rawDimensionKeyWritable.getBytes());
      MetricTimeSeries rawMetricTimeSeries = null;
      Map<DimensionKey, MetricTimeSeries> possibleRollupTimeSeriesMap = new HashMap<DimensionKey, MetricTimeSeries>();
      for (BytesWritable writable : rollupReduceOutputWritableIterable) {
        RollupPhaseTwoReduceOutput temp;
        temp = RollupPhaseTwoReduceOutput.fromBytes(writable.getBytes(),
            metricSchema);
        if (rawMetricTimeSeries == null) {
          rawMetricTimeSeries = temp.getRawTimeSeries();
        }
        possibleRollupTimeSeriesMap.put(temp.getRollupDimensionKey(),
            temp.getRollupTimeSeries());
      }
      //select the roll up dimension key
      DimensionKey selectedRollup = rollupFunc.rollup(rawDimensionKey, possibleRollupTimeSeriesMap, rollupThresholdFunc);
      context.write(new BytesWritable(selectedRollup.toBytes()), new BytesWritable(rawMetricTimeSeries.toBytes()));

    }
  }

  public void run() throws Exception {
    Job job = Job.getInstance(getConf());
    job.setJobName(name);
    job.setJarByClass(RollupPhaseThreeJob.class);

    // Map config
    job.setMapperClass(RollupPhaseThreeMapper.class);
    job.setInputFormatClass(SequenceFileInputFormat.class);
    job.setMapOutputKeyClass(BytesWritable.class);
    job.setMapOutputValueClass(BytesWritable.class);

    // Reduce config
    job.setCombinerClass(RollupPhaseThreeReducer.class);
    job.setReducerClass(RollupPhaseThreeReducer.class);
    job.setOutputKeyClass(BytesWritable.class);
    job.setOutputValueClass(BytesWritable.class);
    job.setOutputFormatClass(SequenceFileOutputFormat.class);
    // rollup phase 2 config
    Configuration configuration = job.getConfiguration();
    String inputPathDir = getAndSetConfiguration(configuration,
        ROLLUP_PHASE3_INPUT_PATH);
    getAndSetConfiguration(configuration, ROLLUP_PHASE3_CONFIG_PATH);
    getAndSetConfiguration(configuration, ROLLUP_PHASE3_OUTPUT_PATH);
    LOG.info("Input path dir: " + inputPathDir);
    for (String inputPath : inputPathDir.split(",")) {
      LOG.info("Adding input:" + inputPath);
      Path input = new Path(inputPath);
      FileInputFormat.addInputPath(job, input);
    }

    FileOutputFormat.setOutputPath(job, new Path(
        getAndCheck(ROLLUP_PHASE3_OUTPUT_PATH.toString())));

    job.waitForCompletion(true);
  }

  private String getAndSetConfiguration(Configuration configuration,
      RollupPhaseThreeConstants constant) {
    String value = getAndCheck(constant.toString());
    configuration.set(constant.toString(), value);
    return value;
  }

  private String getAndCheck(String propName) {
    String propValue = props.getProperty(propName);
    if (propValue == null) {
      throw new IllegalArgumentException(propName + " required property");
    }
    return propValue;
  }

}
