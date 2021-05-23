/*
 * Copyright (C) 2020 The zfoo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.zfoo.app.zapp.common.protocol.user;

import com.zfoo.app.zapp.common.util.TokenUtils;
import com.zfoo.net.core.gateway.IGatewayLoadBalancer;
import com.zfoo.protocol.IPacket;


/**
 * @author jaysunxiao
 * @version 1.0
 * @since 2019-10-15 17:55
 */
public class WebsocketSignInRequest implements IPacket, IGatewayLoadBalancer {

    public static final transient short PROTOCOL_ID = 1000;

    private String token;

    @Override
    public Object loadBalancerConsistentHashObject() {
        var triple = TokenUtils.get(token);
        return triple.getLeft();
    }

    public static short getProtocolId() {
        return PROTOCOL_ID;
    }


    @Override
    public short protocolId() {
        return PROTOCOL_ID;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

}
