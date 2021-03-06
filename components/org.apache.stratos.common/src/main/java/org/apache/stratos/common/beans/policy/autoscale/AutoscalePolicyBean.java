/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.stratos.common.beans.policy.autoscale;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class AutoscalePolicyBean {

    private String id;
    private String displayName;
    private String description;
    private LoadThresholdsBean loadThresholds;
    private boolean isPublic;
    private float instanceRoundingFactor;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }

    
    public LoadThresholdsBean getLoadThresholds() {
        return loadThresholds;
    }

    public void setLoadThresholds(LoadThresholdsBean loadThresholds) {
        this.loadThresholds = loadThresholds;
    }

    public float getInstanceRoundingFactor() {
        return instanceRoundingFactor;
    }

    public void setInstanceRoundingFactor(float instanceRoundingFactor) {
        this.instanceRoundingFactor = instanceRoundingFactor;
    }
}
