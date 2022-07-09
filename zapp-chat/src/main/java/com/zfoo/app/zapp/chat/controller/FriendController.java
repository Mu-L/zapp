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

package com.zfoo.app.zapp.chat.controller;

import com.zfoo.app.zapp.chat.service.IChatMessageService;
import com.zfoo.app.zapp.chat.service.IFriendService;
import com.zfoo.app.zapp.common.constant.AppConstant;
import com.zfoo.app.zapp.common.entity.common.MessageEnum;
import com.zfoo.app.zapp.common.entity.friend.ApplicantEntity;
import com.zfoo.app.zapp.common.entity.friend.FriendEntity;
import com.zfoo.app.zapp.common.protocol.cache.GetUserCacheAnswer;
import com.zfoo.app.zapp.common.protocol.cache.GetUserCacheAsk;
import com.zfoo.app.zapp.common.protocol.friend.NewApplyFriendNotice;
import com.zfoo.app.zapp.common.protocol.friend.NewFriendNotice;
import com.zfoo.app.zapp.common.protocol.friend.model.ApplyFriendVO;
import com.zfoo.app.zapp.common.protocol.friend.operation.*;
import com.zfoo.app.zapp.common.protocol.push.friend.AcceptFriendPush;
import com.zfoo.app.zapp.common.protocol.push.friend.ApplyFriendPush;
import com.zfoo.app.zapp.common.protocol.user.friend.*;
import com.zfoo.app.zapp.common.result.CodeEnum;
import com.zfoo.app.zapp.common.util.CommonUtils;
import com.zfoo.net.NetContext;
import com.zfoo.net.packet.common.Error;
import com.zfoo.net.packet.common.Message;
import com.zfoo.net.router.attachment.GatewayAttachment;
import com.zfoo.net.router.receiver.PacketReceiver;
import com.zfoo.net.session.model.Session;
import com.zfoo.net.task.TaskBus;
import com.zfoo.orm.OrmContext;
import com.zfoo.orm.model.anno.EntityCachesInjection;
import com.zfoo.orm.model.cache.IEntityCaches;
import com.zfoo.protocol.collection.CollectionUtils;
import com.zfoo.protocol.util.StringUtils;
import com.zfoo.scheduler.util.TimeUtils;
import com.zfoo.util.math.HashUtils;
import com.zfoo.util.security.IdUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * @author jaysunxiao
 * @version 1.0
 * @since 2019-11-14 11:53
 */
@Component
public class FriendController {

    @Autowired
    private IFriendService friendService;

    @Autowired
    private IChatMessageService chatMessageService;

    @EntityCachesInjection
    private IEntityCaches<String, ApplicantEntity> applicantEntityCaches;

    @EntityCachesInjection
    private IEntityCaches<String, FriendEntity> friendEntityCaches;

    /**
     * 发起好友申请
     *
     * @param session
     * @param cm
     * @param gatewayAttachment
     */
    @PacketReceiver
    public void atApplyFriendRequest(Session session, ApplyFriendRequest cm, GatewayAttachment gatewayAttachment) {
        var userId = gatewayAttachment.getUid();
        var friendId = cm.getFriendId();

        if (userId != cm.getUserId()) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_ERROR.getCode()), gatewayAttachment);
            return;
        }

        if (!CommonUtils.isUserIdInRange(List.of(friendId)) || friendId == userId) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.USER_NOT_EXIST.getCode()), gatewayAttachment);
            return;
        }

        var id = IdUtils.generateStringId(userId, friendId);

        // 已经是好友
        if (friendService.checkConnected(userId, friendId)) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.FRIEND_ALREADY_ADDED.getCode()), gatewayAttachment);
            return;
        }

        // 如果是黑名单中的好友，则忽略直接返回
        if (friendService.checkBlackListed(userId, friendId)) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.FRIEND_REJECT_MESSAGE.getCode()), gatewayAttachment);
            return;
        }

        // 向user发起rpc
        NetContext.getConsumer()
                .asyncAsk(ApplyFriendLimitAsk.valueOf(userId), Message.class, userId)
                .whenComplete(message -> {
                    // 用户不存在
                    if (!message.success()) {
                        // 返回失败信息给user
                        NetContext.getRouter().send(session, Error.valueOf(cm, message.getCode()), gatewayAttachment);
                        return;
                    }

                    // 向cache服务器发起rpc
                    NetContext.getConsumer()
                            .asyncAsk(GetUserCacheAsk.valueOf(Set.of(userId)), GetUserCacheAnswer.class, userId)
                            .whenComplete(answer -> {
                                // 申请关系
                                var applicantEntity = applicantEntityCaches.load(id);

                                // 以经申请过，则不用重新申请，直接返回
                                if (applicantEntity.getUserId() == userId && applicantEntity.getTargetId() == friendId && applicantEntity.getStatus() == 0) {
                                    NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.FRIEND_ALREADY_APPLY.getCode()), gatewayAttachment);
                                    return;
                                }

                                // 从cache服得到userId的缓存信息
                                var userCacheMap = answer.getUserCacheMap();
                                // userId对应的信息不存在
                                if (CollectionUtils.isEmpty(userCacheMap)) {
                                    NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.USER_NOT_EXIST.getCode()), gatewayAttachment);
                                    return;
                                }

                                // 如果没有申请过，那么就创建一个entity，往数据库记录下申请信息
                                if (StringUtils.isBlank(applicantEntity.getId())) {
                                    var newApplyFriendEntity = ApplicantEntity.valueOf(userId, friendId);
                                    newApplyFriendEntity.setTimestamp(TimeUtils.now());
                                    newApplyFriendEntity.setStatus(0);
                                    applicantEntity = newApplyFriendEntity;
                                    OrmContext.getAccessor().insert(newApplyFriendEntity);
                                    applicantEntityCaches.invalidate(id);
                                }
                                applicantEntity.setStatus(0);
                                applicantEntity.setTimestamp(TimeUtils.now());
                                // db中插入后，再更新到缓存中
                                applicantEntityCaches.update(applicantEntity);


                                var applyFriendVO = ApplyFriendVO.valueOf(applicantEntity.getUserId(), applicantEntity.getStatus(), applicantEntity.getTimestamp());
                                applyFriendVO.setFriendCache(userCacheMap.get(userId));

                                // 把消息直接-->push-->网关-->2个用户
                                // 毕竟看application.xml也知道这个协议是Push模块的. 自己是消费者，向push模块发消息。
                                NetContext.getConsumer().send(ApplyFriendPush.valueOf(friendId, NewApplyFriendNotice.valueOf(applyFriendVO)), IdUtils.generateStringId(userId, friendId));

                                // 把消息发给客户端(经网关转发)：这个session虽然是网关session，但是会直接把消息转发给客户端
                                NetContext.getRouter().send(session, Message.valueOf(cm, CodeEnum.OK.getCode()), gatewayAttachment);
                            });
                });
    }


    /**
     * 点击同意添加好友
     *
     * @param session
     * @param cm
     * @param gatewayAttachment
     */
    @PacketReceiver
    public void atAcceptFriendRequest(Session session, AcceptFriendRequest cm, GatewayAttachment gatewayAttachment) {
        var userId = gatewayAttachment.getUid();
        var friendId = cm.getFriendId();

        if (userId != cm.getUserId()) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_ERROR.getCode()), gatewayAttachment);
            return;
        }

        if (!CommonUtils.isUserIdInRange(List.of(friendId)) || friendId == userId) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_EMPTY.getCode()), gatewayAttachment);
            return;
        }

        // 已经是好友
        if (friendService.checkConnected(userId, friendId)) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.FRIEND_ALREADY_ADDED.getCode()), gatewayAttachment);
            return;
        }

        var id = IdUtils.generateStringId(userId, friendId);
        var applicantEntity = applicantEntityCaches.load(id);
        if (StringUtils.isBlank(applicantEntity.id())) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_ERROR_ONE.getCode()), gatewayAttachment);
            return;
        }

        // chat服向user服发起rpc，请求记录下userId添加了friendId为好友（修改UserEntity表）
        NetContext.getConsumer()
                .asyncAsk(UserAcceptFriendAsk.valueOf(userId, friendId), Message.class, userId)
                .whenComplete(messageA -> {
                    if (!messageA.success()) {
                        // 返回失败信息给user
                        NetContext.getRouter().send(session, Error.valueOf(cm, messageA.getCode()), gatewayAttachment);
                        return;
                    }

                    // chat服向user服发起rpc，请求记录下friendId添加了userId为好友（修改UserEntity表）
                    NetContext.getConsumer()
                            .asyncAsk(UserAcceptFriendAsk.valueOf(friendId, userId), Message.class, friendId)
                            .whenComplete(messageB -> {
                                if (!messageB.success()) {
                                    // 返回失败信息给user
                                    NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.FAIL.getCode()), gatewayAttachment);
                                    return;
                                }

                                // 删除一下申请记录
                                TaskBus.executor(HashUtils.fnvHash(cm.loadBalancerConsistentHashObject())).execute(() -> {
                                    // 先删除缓存
                                    applicantEntityCaches.invalidate(id);
                                    // 再删除数据库中的数据
                                    OrmContext.getAccessor().delete(id, ApplicantEntity.class);

                                    // 修改FriendEntity表，记录好友关系
                                    var friendEntity = friendEntityCaches.load(id);
                                    if (StringUtils.isBlank(friendEntity.getId())) {
                                        var newEntity = FriendEntity.valueOf(userId, friendId);
                                        newEntity.setConnected(true);
                                        friendEntity = newEntity;
                                        OrmContext.getAccessor().insert(newEntity);
                                        friendEntityCaches.invalidate(id);
                                    }
                                    friendEntity.setConnected(true);
                                    friendEntityCaches.update(friendEntity);

                                    // 给客户端返回消息：添加好友成功
                                    NetContext.getRouter().send(session, Message.valueOf(cm, CodeEnum.OK.getCode()), gatewayAttachment);

                                    NetContext.getConsumer()
                                            .asyncAsk(GetUserCacheAsk.valueOf(Set.of(userId, friendId)), GetUserCacheAnswer.class, cm.loadBalancerConsistentHashObject())
                                            .whenComplete(userCacheAnswer -> {
                                                var userCacheMap = userCacheAnswer.getUserCacheMap();
                                                // 通过Consumer给网关推送信息。 网关再把消息发给2个客户端。
                                                // 因为玩家a添加玩家b为好友。 在这里只能给玩家a发消息，但是添加好友是双向的，要想给玩家b发消息，只能先发给网关了。
                                                NetContext.getConsumer().send(AcceptFriendPush.valueOf(List.of(userId, friendId),
                                                        NewFriendNotice.valueOf(userCacheMap.get(userId), userCacheMap.get(friendId))), IdUtils.generateStringId(userId, friendId));
                                                // 第一条打招呼消息
                                                chatMessageService.chatToFriend(userId, friendId, MessageEnum.TEXT, AppConstant.FRIEND_FIRST_MESSAGE);
                                            });
                                });
                            });
                });
    }


    @PacketReceiver
    public void atRejectFriendRequest(Session session, RejectFriendRequest cm, GatewayAttachment gatewayAttachment) {
        var userId = gatewayAttachment.getUid();
        var friendId = cm.getFriendId();

        if (userId != cm.getUserId()) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_ERROR.getCode()), gatewayAttachment);
            return;
        }

        if (!CommonUtils.isUserIdInRange(List.of(friendId)) || friendId == userId) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_EMPTY.getCode()), gatewayAttachment);
            return;
        }
        var id = IdUtils.generateStringId(userId, friendId);
        var friendApplyEntity = applicantEntityCaches.load(id);
        if (StringUtils.isBlank(friendApplyEntity.id())) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_ERROR_ONE.getCode()), gatewayAttachment);
            return;
        }

        applicantEntityCaches.invalidate(id);
        OrmContext.getAccessor().delete(friendApplyEntity);

        NetContext.getRouter().send(session, RejectFriendResponse.valueOf(friendId), gatewayAttachment);
    }


    @PacketReceiver
    public void atDeleteFriendRequest(Session session, DeleteFriendRequest cm, GatewayAttachment gatewayAttachment) {
        var userId = gatewayAttachment.getUid();
        var friendId = cm.getFriendId();

        if (userId != cm.getUserId()) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_ERROR.getCode()), gatewayAttachment);
            return;
        }

        if (!CommonUtils.isUserIdInRange(List.of(friendId)) || friendId == userId) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_EMPTY.getCode()), gatewayAttachment);
            return;
        }

        var id = IdUtils.generateStringId(userId, friendId);
        var friendEntity = friendEntityCaches.load(id);
        if (StringUtils.isBlank(friendEntity.getId())) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_ERROR_ONE.getCode()), gatewayAttachment);
            return;
        }
        friendEntity.setConnected(false);
        friendEntityCaches.update(friendEntity);

        NetContext.getConsumer()
                .asyncAsk(DeleteFriendAsk.valueOf(userId, friendId), Message.class, userId)
                .whenComplete(message -> {
                    if (!message.success()) {
                        // 返回失败信息给user
                        NetContext.getRouter().send(session, Error.valueOf(cm, message.getCode(), message.getMessage()), gatewayAttachment);
                        return;
                    }

                    NetContext.getRouter().send(session, DeleteFriendResponse.valueOf(friendId), gatewayAttachment);
                });
    }

    @PacketReceiver
    public void atBlacklistRequest(Session session, BlacklistRequest cm, GatewayAttachment gatewayAttachment) {
        var userId = gatewayAttachment.getUid();
        var targetId = cm.getTargetId();

        if (userId != cm.getUserId()) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_ERROR.getCode()), gatewayAttachment);
            return;
        }

        if (!CommonUtils.isUserIdInRange(List.of(targetId)) || targetId == userId) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_EMPTY.getCode()), gatewayAttachment);
            return;
        }

        var id = IdUtils.generateStringId(userId, targetId);
        var friendEntity = friendEntityCaches.load(id);

        if (StringUtils.isBlank(friendEntity.getId())) {
            var newEntity = FriendEntity.valueOf(userId, targetId);
            newEntity.makeBlacklist(targetId);
            friendEntity = newEntity;
            OrmContext.getAccessor().insert(newEntity);
            friendEntityCaches.invalidate(id);
        }

        friendEntity.makeBlacklist(targetId);
        friendEntityCaches.update(friendEntity);

        NetContext.getConsumer()
                .asyncAsk(BlacklistAsk.valueOf(userId, targetId), Message.class, userId)
                .whenComplete(message -> {
                    if (!message.success()) {
                        NetContext.getRouter().send(session, Error.valueOf(cm, message.getCode(), message.getMessage()), gatewayAttachment);
                        return;
                    }

                    NetContext.getConsumer()
                            .asyncAsk(GetUserCacheAsk.valueOf(Set.of(targetId)), GetUserCacheAnswer.class, targetId)
                            .whenComplete(answer -> {
                                var userCacheMap = answer.getUserCacheMap();
                                NetContext.getRouter().send(session, BlacklistResponse.valueOf(userCacheMap.get(targetId)), gatewayAttachment);
                            });
                });
    }

    @PacketReceiver
    public void atBlacklistCancelRequest(Session session, BlacklistCancelRequest cm, GatewayAttachment gatewayAttachment) {
        var userId = gatewayAttachment.getUid();
        var targetId = cm.getTargetId();

        if (userId != cm.getUserId()) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_ERROR.getCode()), gatewayAttachment);
            return;
        }

        if (!CommonUtils.isUserIdInRange(List.of(targetId)) || targetId == userId) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_EMPTY.getCode()), gatewayAttachment);
            return;
        }

        var id = IdUtils.generateStringId(userId, targetId);
        var friendEntity = friendEntityCaches.load(id);

        if (StringUtils.isBlank(friendEntity.getId())) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_ERROR_ONE.getCode()), gatewayAttachment);
            return;
        }

        friendEntity.cancelBlacklist(targetId);
        friendEntityCaches.update(friendEntity);

        NetContext.getConsumer()
                .asyncAsk(BlacklistCancelAsk.valueOf(userId, targetId), Message.class, userId)
                .whenComplete(message -> {
                    if (!message.success()) {
                        NetContext.getRouter().send(session, Error.valueOf(cm, message.getCode(), message.getMessage()), gatewayAttachment);
                        return;
                    }

                    NetContext.getRouter().send(session, BlacklistCancelResponse.valueOf(targetId), gatewayAttachment);
                });
    }

    @PacketReceiver
    public void atMarkFriendRequest(Session session, MarkFriendRequest cm, GatewayAttachment gatewayAttachment) {
        var userId = gatewayAttachment.getUid();
        var friendId = cm.getFriendId();
        var tag = cm.getTag();

        if (userId != cm.getUserId()) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_ERROR.getCode()), gatewayAttachment);
            return;
        }

        if (!CommonUtils.isUserIdInRange(List.of(friendId)) || friendId == userId) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_EMPTY.getCode()), gatewayAttachment);
            return;
        }

        if (StringUtils.isBlank(tag)) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_EMPTY_ONE.getCode()), gatewayAttachment);
            return;
        }

        if (!friendService.checkConnected(userId, friendId)) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.FRIEND_NOT_ADDED.getCode()), gatewayAttachment);
            return;
        }

        var id = IdUtils.generateStringId(userId, friendId);
        var friendEntity = friendEntityCaches.load(id);
        if (StringUtils.isBlank(friendEntity.id())) {
            NetContext.getRouter().send(session, Error.valueOf(cm, CodeEnum.PARAMETER_ERROR_ONE.getCode()), gatewayAttachment);
            return;
        }
        friendEntity.markTag(userId, tag);
        friendEntityCaches.update(friendEntity);

        NetContext.getRouter().send(session, MarkFriendResponse.valueOf(friendId, tag), gatewayAttachment);
    }

}
