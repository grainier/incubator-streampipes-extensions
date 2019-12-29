/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.streampipes.processors.enricher.flink.processor.math.mathop;

import org.apache.streampipes.model.DataProcessorType;
import org.apache.streampipes.model.graph.DataProcessorDescription;
import org.apache.streampipes.model.graph.DataProcessorInvocation;
import org.apache.streampipes.model.schema.PropertyScope;
import org.apache.streampipes.processors.enricher.flink.config.EnricherFlinkConfig;
import org.apache.streampipes.processors.enricher.flink.processor.math.operation.Operation;
import org.apache.streampipes.processors.enricher.flink.processor.math.operation.OperationAddition;
import org.apache.streampipes.processors.enricher.flink.processor.math.operation.OperationDivide;
import org.apache.streampipes.processors.enricher.flink.processor.math.operation.OperationModulo;
import org.apache.streampipes.processors.enricher.flink.processor.math.operation.OperationMultiply;
import org.apache.streampipes.processors.enricher.flink.processor.math.operation.OperationSubtracting;
import org.apache.streampipes.sdk.builder.ProcessingElementBuilder;
import org.apache.streampipes.sdk.builder.StreamRequirementsBuilder;
import org.apache.streampipes.sdk.extractor.ProcessingElementParameterExtractor;
import org.apache.streampipes.sdk.helpers.EpProperties;
import org.apache.streampipes.sdk.helpers.EpRequirements;
import org.apache.streampipes.sdk.helpers.Labels;
import org.apache.streampipes.sdk.helpers.Locales;
import org.apache.streampipes.sdk.helpers.Options;
import org.apache.streampipes.sdk.helpers.OutputStrategies;
import org.apache.streampipes.sdk.utils.Assets;
import org.apache.streampipes.vocabulary.SO;
import org.apache.streampipes.wrapper.flink.FlinkDataProcessorDeclarer;
import org.apache.streampipes.wrapper.flink.FlinkDataProcessorRuntime;

public class MathOpController extends FlinkDataProcessorDeclarer<MathOpParameters> {

  private final String RESULT_FIELD = "calculationResult";
  private final String LEFT_OPERAND = "leftOperand";
  private final String RIGHT_OPERAND = "rightOperand";
  private final String OPERATION = "operation";

  @Override
  public DataProcessorDescription declareModel() {
    return ProcessingElementBuilder.create("org.apache.streampipes.processors.enricher.flink.processor.math.mathop")
            .withAssets(Assets.DOCUMENTATION, Assets.ICON)
            .withLocales(Locales.EN)
            .category(DataProcessorType.ALGORITHM)
            .requiredStream(StreamRequirementsBuilder
                    .create()
                    .requiredPropertyWithUnaryMapping(EpRequirements.numberReq(),
                            Labels.withId(LEFT_OPERAND),
                            PropertyScope.NONE)
                    .requiredPropertyWithUnaryMapping(EpRequirements.numberReq(),
                            Labels.withId(RIGHT_OPERAND),
                            PropertyScope.NONE)
                    .build())
            .outputStrategy(
                    OutputStrategies.append(
                            EpProperties.numberEp(Labels.empty(), RESULT_FIELD, SO.Number)))
            .requiredSingleValueSelection(Labels.withId(OPERATION), Options.from("+", "-", "/",
                    "*", "%"))
            .build();
  }

  @Override
  public FlinkDataProcessorRuntime<MathOpParameters> getRuntime(DataProcessorInvocation graph, ProcessingElementParameterExtractor extractor) {
    String leftOperand = extractor.mappingPropertyValue(LEFT_OPERAND);
    String rightOperand = extractor.mappingPropertyValue(RIGHT_OPERAND);
    String operation = extractor.selectedSingleValue(OPERATION, String.class);

    Operation arithmeticOperation = null;
    switch (operation) {
      case "+":
        arithmeticOperation = new OperationAddition();
        break;
      case "-":
        arithmeticOperation = new OperationSubtracting();
        break;
      case "*":
        arithmeticOperation = new OperationMultiply();
        break;
      case "/":
        arithmeticOperation = new OperationDivide();
        break;
      case "%":
        arithmeticOperation = new OperationModulo();
    }

    MathOpParameters parameters = new MathOpParameters(graph, arithmeticOperation, leftOperand, rightOperand, RESULT_FIELD);

    return new MathOpProgram(parameters, EnricherFlinkConfig.INSTANCE.getDebug());
  }
}