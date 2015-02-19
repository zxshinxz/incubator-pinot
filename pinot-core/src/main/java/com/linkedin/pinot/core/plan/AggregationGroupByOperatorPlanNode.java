package com.linkedin.pinot.core.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import com.linkedin.pinot.common.request.AggregationInfo;
import com.linkedin.pinot.common.request.BrokerRequest;
import com.linkedin.pinot.core.common.Operator;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.operator.query.AggregationFunctionGroupByOperator;
import com.linkedin.pinot.core.operator.query.MAggregationGroupByOperator;
import com.linkedin.pinot.core.query.aggregation.AggregationFunctionUtils;


/**
 * AggregationGroupByOperatorPlanNode takes care of how to apply multiple aggregation
 * functions and groupBy query to an IndexSegment.
 *
 * @author xiafu
 *
 */
public class AggregationGroupByOperatorPlanNode implements PlanNode {
  private static final Logger _logger = Logger.getLogger("QueryPlanLog");
  private final IndexSegment _indexSegment;
  private final BrokerRequest _brokerRequest;
  private final ProjectionPlanNode _projectionPlanNode;
  private final List<AggregationFunctionGroupByPlanNode> _aggregationFunctionGroupByPlanNodes =
      new ArrayList<AggregationFunctionGroupByPlanNode>();
  private final AggregationGroupByImplementationType _aggregationGroupByImplementationType;

  public AggregationGroupByOperatorPlanNode(IndexSegment indexSegment, BrokerRequest query,
      AggregationGroupByImplementationType aggregationGroupByImplementationType) {
    _indexSegment = indexSegment;
    _brokerRequest = query;
    _aggregationGroupByImplementationType = aggregationGroupByImplementationType;
    _projectionPlanNode =
        new ProjectionPlanNode(_indexSegment, getAggregationGroupByRelatedColumns(), new DocIdSetPlanNode(
            _indexSegment, _brokerRequest, 10000));
    for (int i = 0; i < _brokerRequest.getAggregationsInfo().size(); ++i) {
      AggregationInfo aggregationInfo = _brokerRequest.getAggregationsInfo().get(i);
      boolean hasDictionary = AggregationFunctionUtils.isAggregationFunctionWithDictionary(aggregationInfo, _indexSegment);
      _aggregationFunctionGroupByPlanNodes.add(new AggregationFunctionGroupByPlanNode(aggregationInfo, _brokerRequest.getGroupBy(), _projectionPlanNode,
          _aggregationGroupByImplementationType, hasDictionary));
    }
  }

  private String[] getAggregationGroupByRelatedColumns() {
    Set<String> aggregationGroupByRelatedColumns = new HashSet<String>();
    for (AggregationInfo aggregationInfo : _brokerRequest.getAggregationsInfo()) {
      if (aggregationInfo.getAggregationType().equalsIgnoreCase("count")) {
        continue;
      }
      String columns = aggregationInfo.getAggregationParams().get("column").trim();
      aggregationGroupByRelatedColumns.addAll(Arrays.asList(columns.split(",")));
    }
    aggregationGroupByRelatedColumns.addAll(_brokerRequest.getGroupBy().getColumns());
    return aggregationGroupByRelatedColumns.toArray(new String[0]);
  }

  @Override
  public Operator run() {
    List<AggregationFunctionGroupByOperator> aggregationFunctionOperatorList =
        new ArrayList<AggregationFunctionGroupByOperator>();
    for (AggregationFunctionGroupByPlanNode aggregationFunctionGroupByPlanNode : _aggregationFunctionGroupByPlanNodes) {
      aggregationFunctionOperatorList
          .add((AggregationFunctionGroupByOperator) aggregationFunctionGroupByPlanNode.run());
    }
    return new MAggregationGroupByOperator(_indexSegment, _brokerRequest.getAggregationsInfo(),
        _brokerRequest.getGroupBy(), _projectionPlanNode.run(), aggregationFunctionOperatorList);
  }

  @Override
  public void showTree(String prefix) {
    _logger.debug(prefix + "Inner-Segment Plan Node :");
    _logger.debug(prefix + "Operator: MAggregationGroupByOperator");
    _logger.debug(prefix + "Argument 0: Projection - ");
    _projectionPlanNode.showTree(prefix + "    ");
    for (int i = 0; i < _brokerRequest.getAggregationsInfo().size(); ++i) {
      _logger.debug(prefix + "Argument " + (i + 1) + ": AggregationGroupBy  - ");
      _aggregationFunctionGroupByPlanNodes.get(i).showTree(prefix + "    ");
    }
  }

  public enum AggregationGroupByImplementationType {
    NoDictionary,
    Dictionary,
    DictionaryAndTrie
  }
}
