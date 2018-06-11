/*
 *   Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.transport.http.netty.sender.http2;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2EventAdapter;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpConversionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.common.Util;
import org.wso2.transport.http.netty.message.DefaultListener;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.transport.http.netty.message.Http2PushPromise;
import org.wso2.transport.http.netty.message.HttpCarbonResponse;
import org.wso2.transport.http.netty.message.PooledDataStreamerFactory;

/**
 * {@code ClientInboundHandler} listen to HTTP/2 Events received from the HTTP/2 backend service
 * and construct response messages.
 * <p>
 * And also this is responsible for notifying the HTTP Response Listener as well.
 */
public class ClientInboundHandler extends Http2EventAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClientInboundHandler.class);

    private Http2ClientChannel http2ClientChannel;

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
                          boolean endOfStream) throws Http2Exception {
        if (log.isDebugEnabled()) {
            log.debug("Reading data on channel: {} with stream id: {}, isEndOfStream: {}",
                      http2ClientChannel.toString(), streamId, endOfStream);
        }

        for (Http2DataEventListener listener : http2ClientChannel.getDataEventListeners()) {
            if (!listener.onDataRead(ctx, streamId, data, endOfStream)) {
                return data.readableBytes() + padding;
            }
        }

        OutboundMsgHolder outboundMsgHolder = http2ClientChannel.getInFlightMessage(streamId);
        boolean isServerPush = false;
        if (outboundMsgHolder == null) {
            outboundMsgHolder = http2ClientChannel.getPromisedMessage(streamId);
            if (outboundMsgHolder != null) {
                isServerPush = true;
            } else {
                log.warn("Data Frame received on channel: {} with invalid stream id: {}",
                         http2ClientChannel.toString(), streamId);
                return 0;
            }
        }
        if (isServerPush) {
            HTTPCarbonMessage responseMessage = outboundMsgHolder.getPushResponse(streamId);
            if (endOfStream) {
                responseMessage.addHttpContent(new DefaultLastHttpContent(data.retain()));
                http2ClientChannel.removePromisedMessage(streamId);
            } else {
                responseMessage.addHttpContent(new DefaultHttpContent(data.retain()));
            }
        } else {
            HTTPCarbonMessage responseMessage = outboundMsgHolder.getResponse();
            if (endOfStream) {
                responseMessage.addHttpContent(new DefaultLastHttpContent(data.retain()));
                http2ClientChannel.removeInFlightMessage(streamId);
            } else {
                responseMessage.addHttpContent(new DefaultHttpContent(data.retain()));
            }
        }
        return data.readableBytes() + padding;
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
                              int streamDependency, short weight, boolean exclusive, int padding,
                              boolean endStream) throws Http2Exception {
        this.onHeadersRead(ctx, streamId, headers, padding, endStream);
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
                              int padding, boolean endStream) throws Http2Exception {
        if (log.isDebugEnabled()) {
            log.debug("Reading Http2 headers on channel: {} with stream id: {}, isEndOfStream: {}",
                      http2ClientChannel.toString(), streamId, endStream);
        }

        for (Http2DataEventListener listener : http2ClientChannel.getDataEventListeners()) {
            if (!listener.onHeadersRead(ctx, streamId, headers, endStream)) {
                return;
            }
        }

        OutboundMsgHolder outboundMsgHolder = http2ClientChannel.getInFlightMessage(streamId);
        boolean isServerPush = false;
        if (outboundMsgHolder == null) {
            outboundMsgHolder = http2ClientChannel.getPromisedMessage(streamId);
            if (outboundMsgHolder != null) {
                isServerPush = true;
            } else {
                log.warn("Header Frame received on channel: {} with invalid stream id: {} ",
                         http2ClientChannel.toString(), streamId);
                return;
            }
        }

        if (isServerPush) {
            HTTPCarbonMessage responseMessage = outboundMsgHolder.getPushResponse(streamId);
            // Create response carbon message. if response message doesn't exist.
            if (responseMessage == null) {
                responseMessage = setupResponseCarbonMessage(ctx, streamId, headers, outboundMsgHolder);
                outboundMsgHolder.addPushResponse(streamId, (HttpCarbonResponse) responseMessage);
            }
            if (endStream) {
                onTrailersRead(streamId, headers, outboundMsgHolder, responseMessage);
            }
        } else {
            HTTPCarbonMessage responseMessage = outboundMsgHolder.getResponse();
            // Create response carbon message. if response message doesn't exist.
            if (responseMessage == null) {
                responseMessage = setupResponseCarbonMessage(ctx, streamId, headers, outboundMsgHolder);
                outboundMsgHolder.setResponse((HttpCarbonResponse) responseMessage);
            }
            if (endStream) {
                onTrailersRead(streamId, headers, outboundMsgHolder, responseMessage);
            }
        }
    }

    private void onTrailersRead(int streamId, Http2Headers headers, OutboundMsgHolder outboundMsgHolder,
                                HTTPCarbonMessage responseMessage) {

        HttpVersion version = new HttpVersion(Constants.HTTP_VERSION_2_0, true);
        LastHttpContent lastHttpContent = new DefaultLastHttpContent();
        HttpHeaders trailers = lastHttpContent.trailingHeaders();

        try {
            HttpConversionUtil.addHttp2ToHttpHeaders(
                    streamId, headers, trailers, version, true, false);
        } catch (Http2Exception e) {
            outboundMsgHolder.getResponseFuture().
                    notifyHttpListener(new Exception("Error while setting http headers", e));
        }
        responseMessage.addHttpContent(lastHttpContent);
        http2ClientChannel.removeInFlightMessage(streamId);
    }

    @Override
    public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings)
            throws Http2Exception {
        log.debug("Http2FrameListenAdapter.onSettingRead()");
        ctx.fireChannelRead(settings);
        super.onSettingsRead(ctx, settings);
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) throws Http2Exception {
        log.warn("RST received on channel: {} for streamId: {} errorCode: {}",
                 http2ClientChannel.toString(), streamId, errorCode);
        OutboundMsgHolder outboundMsgHolder = http2ClientChannel.getInFlightMessage(streamId);
        if (outboundMsgHolder != null) {
            outboundMsgHolder.getResponseFuture().
                    notifyHttpListener(new Exception("HTTP/2 stream " + streamId + " reset by the remote peer"));
        }
    }

    @Override
    public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
                                  Http2Headers headers, int padding) throws Http2Exception {
        if (log.isDebugEnabled()) {
            log.debug("Received a push promise on channel: {} over stream id: {}, promisedStreamId: {}",
                      http2ClientChannel.toString(), streamId, promisedStreamId);
        }
        for (Http2DataEventListener listener : http2ClientChannel.getDataEventListeners()) {
            if (!listener.onPushPromiseRead(ctx, streamId, headers, false)) {
                return;
            }
        }

        OutboundMsgHolder outboundMsgHolder = http2ClientChannel.getInFlightMessage(streamId);
        if (outboundMsgHolder == null) {
            log.warn("Push promise received in channel: {} over invalid stream id : {}",
                     http2ClientChannel.toString(), streamId);
            return;
        }
        http2ClientChannel.putPromisedMessage(promisedStreamId, outboundMsgHolder);
        http2ClientChannel.getDataEventListeners().
                forEach(dataEventListener -> dataEventListener.onStreamInit(ctx, promisedStreamId));
        Http2PushPromise pushPromise =
                new Http2PushPromise(Util.createHttpRequestFromHttp2Headers(headers, streamId), outboundMsgHolder);
        pushPromise.setPromisedStreamId(promisedStreamId);
        pushPromise.setStreamId(streamId);
        outboundMsgHolder.addPromise(pushPromise);
    }

    @Override
    public void onGoAwaySent(int lastStreamId, long errorCode, ByteBuf debugData) {
        http2ClientChannel.destroy();
    }

    @Override
    public void onGoAwayReceived(int lastStreamId, long errorCode, ByteBuf debugData) {
        http2ClientChannel.destroy();
    }

    /**
     * Sets the {@code TargetChannel} associated with the ClientInboundHandler.
     *
     * @param http2ClientChannel the associated TargetChannel
     */
    public void setHttp2ClientChannel(Http2ClientChannel http2ClientChannel) {
        this.http2ClientChannel = http2ClientChannel;
    }

    private HttpCarbonResponse setupResponseCarbonMessage(ChannelHandlerContext ctx, int streamId,
                                                          Http2Headers http2Headers,
                                                          OutboundMsgHolder outboundMsgHolder) {
        // Create HTTP Response
        CharSequence status = http2Headers.status();
        HttpResponseStatus responseStatus;
        try {
            responseStatus = HttpConversionUtil.parseStatus(status);
        } catch (Http2Exception e) {
            responseStatus = HttpResponseStatus.BAD_GATEWAY;
        }
        HttpVersion version = new HttpVersion(Constants.HTTP_VERSION_2_0, true);
        HttpResponse httpResponse = new DefaultHttpResponse(version, responseStatus);

        // Set headers
        try {
            HttpConversionUtil.addHttp2ToHttpHeaders(
                    streamId, http2Headers, httpResponse.headers(), version, false, false);
        } catch (Http2Exception e) {
            outboundMsgHolder.getResponseFuture().
                    notifyHttpListener(new Exception("Error while setting http headers", e));
        }
        // Create HTTP Carbon Response
        HttpCarbonResponse responseCarbonMsg = new HttpCarbonResponse(httpResponse, new DefaultListener(ctx));

        // Setting properties of the HTTP Carbon Response
        responseCarbonMsg.setProperty(Constants.POOLED_BYTE_BUFFER_FACTORY, new PooledDataStreamerFactory(ctx.alloc()));
        responseCarbonMsg.setProperty(Constants.DIRECTION, Constants.DIRECTION_RESPONSE);
        responseCarbonMsg.setProperty(Constants.HTTP_STATUS_CODE, httpResponse.status().code());

        /* copy required properties for service chaining from incoming carbon message to the response carbon message
        copy shared worker pool */
        responseCarbonMsg.setProperty(Constants.EXECUTOR_WORKER_POOL,
                                      outboundMsgHolder.getRequest().getProperty(Constants.EXECUTOR_WORKER_POOL));
        return responseCarbonMsg;
    }
}
