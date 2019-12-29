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

package org.apache.streampipes.connect.adapters.influxdb;

import org.apache.streampipes.commons.exceptions.SpRuntimeException;
import org.apache.streampipes.connect.adapter.Adapter;
import org.apache.streampipes.connect.adapter.exception.AdapterException;
import org.apache.streampipes.connect.adapter.exception.ParseException;
import org.apache.streampipes.connect.adapter.model.specific.SpecificDataStreamAdapter;
import org.apache.streampipes.connect.adapter.sdk.ParameterExtractor;
import org.apache.streampipes.model.connect.adapter.SpecificAdapterStreamDescription;
import org.apache.streampipes.model.connect.guess.GuessSchema;
import org.apache.streampipes.sdk.builder.adapter.SpecificDataStreamAdapterBuilder;
import org.apache.streampipes.sdk.helpers.Labels;
import org.apache.streampipes.sdk.helpers.Options;
import org.apache.streampipes.sdk.helpers.Tuple2;

import java.util.List;
import java.util.Map;

import static org.apache.streampipes.connect.adapters.influxdb.InfluxDbClient.getTimestamp;

public class InfluxDbStreamAdapter extends SpecificDataStreamAdapter {

    public static final String ID = "http://streampipes.org/adapter/specific/influxdbstream";

    private static final String POLLING_INTERVAL = "pollingInterval";

    private InfluxDbClient influxDbClient;

    private Thread pollingThread;
    private int pollingInterval;

    public static class PollingThread implements Runnable {
        private int pollingInterval;

        private InfluxDbClient influxDbClient;
        private InfluxDbStreamAdapter influxDbStreamAdapter;

        PollingThread(InfluxDbStreamAdapter influxDbStreamAdapter, int pollingInterval) throws AdapterException {
            this.pollingInterval = pollingInterval;
            this.influxDbStreamAdapter = influxDbStreamAdapter;
            this.influxDbClient = influxDbStreamAdapter.getInfluxDbClient();

            influxDbClient.connect();
            influxDbClient.loadColumns();
        }

        @Override
        public void run() {
            if (!influxDbClient.isConnected()) {
                System.out.println("Cannot start PollingThread, when the client is not connected");
                return;
            }
            // Checking the most recent timestamp
            // Timestamp is a string, because a long might not be big enough (it includes nano seconds)
            String lastTimestamp;
            try {
                lastTimestamp = getNewestTimestamp();
            } catch (SpRuntimeException e) {
                System.out.println(e.getMessage());
                return;
            }

            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(pollingInterval);
                } catch (InterruptedException e) {
                    break;
                }
                List<List<Object>> queryResult = influxDbClient.query("SELECT " + influxDbClient.getColumnsString()
                        + " FROM " + influxDbClient.getMeasurement() + " WHERE time > " + lastTimestamp + " ORDER BY time ASC ");
                if (queryResult.size() > 0) {
                    // The last element has the highest timestamp (ordered asc) -> Set the new latest timestamp
                    lastTimestamp = getTimestamp((String)queryResult.get(queryResult.size() - 1).get(0));

                    for (List<Object> value : queryResult) {
                        try {
                            Map<String, Object> out = influxDbClient.extractEvent(value);
                            if (out != null) {
                                influxDbStreamAdapter.send(out);
                            }
                        } catch (SpRuntimeException e) {
                            System.out.println("Error: " + e.getMessage());
                        }
                    }
                }
            }
            influxDbClient.disconnect();
        }

        // Returns the newest timestamp in the measurement as unix timestamp in Nanoseconds.
        // If no entry is found, a SpRuntimeException is thrown
        String getNewestTimestamp() throws SpRuntimeException {
            List<List<Object>> queryResult = influxDbClient.query("SELECT * FROM " + influxDbClient.getMeasurement()
                    + " ORDER BY time DESC LIMIT 1");
            if (queryResult.size() > 0) {
                return getTimestamp((String)queryResult.get(0).get(0));
            } else {
                throw new SpRuntimeException("No entry found in query");
            }
        }
    }

    private InfluxDbClient getInfluxDbClient() {
        return influxDbClient;
    }

    public InfluxDbStreamAdapter() {
    }

    public InfluxDbStreamAdapter(SpecificAdapterStreamDescription specificAdapterStreamDescription) {
        super(specificAdapterStreamDescription);

        getConfigurations(specificAdapterStreamDescription);
    }

    @Override
    public SpecificAdapterStreamDescription declareModel() {
        SpecificAdapterStreamDescription description = SpecificDataStreamAdapterBuilder.create(
                ID,
                "InfluxDB Stream Adapter",
                "Creates a data stream for a InfluxDB measurement")
                .requiredTextParameter(Labels.from(InfluxDbClient.HOST, "Hostname", "Hostname of the InfluxDB Server (needs an \"http://\" in front)"))
                .requiredIntegerParameter(Labels.from(InfluxDbClient.PORT, "Port", "Port of the InfluxDB Server (e.g. 8086"), 8086)
                .requiredTextParameter(Labels.from(InfluxDbClient.DATABASE, "Database", "Name of the database"))
                .requiredTextParameter(Labels.from(InfluxDbClient.MEASUREMENT, "Measurement", "Name of the measurement, which should be observed"))
                .requiredTextParameter(Labels.from(InfluxDbClient.USERNAME, "Username", "The username to log into the InfluxDB"))
                .requiredSecret(Labels.from(InfluxDbClient.PASSWORD, "Password", "The password to log into the InfluxDB"))
                .requiredIntegerParameter(Labels.from(POLLING_INTERVAL, "Polling interval (MS)", "How often the database should be checked for new entries (in MS)"))
                .requiredSingleValueSelection(Labels.from(InfluxDbClient.REPLACE_NULL_VALUES, "Replace Null Values", "Should null values in the incoming data be replace by defaults? If not, these events are skipped"),
                        Options.from(
                                new Tuple2<>("Yes", InfluxDbClient.DO_REPLACE),
                                new Tuple2<>("No", InfluxDbClient.DO_NOT_REPLACE)))
                .build();

        description.setAppId(ID);
        return description;
    }

    @Override
    public void startAdapter() throws AdapterException {
        pollingThread = new Thread(new PollingThread(this, pollingInterval));
        pollingThread.start();
    }

    @Override
    public void stopAdapter() throws AdapterException {
        // Signaling the thread to stop and then disconnect from the server
        pollingThread.interrupt();
        try {
            pollingThread.join();
        } catch (InterruptedException e) {
            throw new AdapterException("Unexpected Error while joining polling thread: " + e.getMessage());
        }
    }

    @Override
    public Adapter getInstance(SpecificAdapterStreamDescription adapterDescription) {
        return new InfluxDbStreamAdapter(adapterDescription);
    }

    @Override
    public GuessSchema getSchema(SpecificAdapterStreamDescription adapterDescription)
            throws AdapterException, ParseException {
        getConfigurations(adapterDescription);
        return influxDbClient.getSchema();
    }

    @Override
    public String getId() {
        return ID;
    }

    private void send(Map<String, Object> map) {
        adapterPipeline.process(map);
    }

    private void getConfigurations(SpecificAdapterStreamDescription adapterDescription) {
        ParameterExtractor extractor = new ParameterExtractor(adapterDescription.getConfig());

        pollingInterval = extractor.singleValue(POLLING_INTERVAL, Integer.class);
        String replace = extractor.selectedSingleValueInternalName(InfluxDbClient.REPLACE_NULL_VALUES);

        influxDbClient = new InfluxDbClient(
                extractor.singleValue(InfluxDbClient.HOST, String.class),
                extractor.singleValue(InfluxDbClient.PORT, Integer.class),
                extractor.singleValue(InfluxDbClient.DATABASE, String.class),
                extractor.singleValue(InfluxDbClient.MEASUREMENT, String.class),
                extractor.singleValue(InfluxDbClient.USERNAME, String.class),
                extractor.secretValue(InfluxDbClient.PASSWORD),
                replace.equals(InfluxDbClient.DO_REPLACE));

    }
}