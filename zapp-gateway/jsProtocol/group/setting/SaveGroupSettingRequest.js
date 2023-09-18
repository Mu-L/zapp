// @author jaysunxiao
// @version 1.0
// @since 2020-04-23 14:20
const SaveGroupSettingRequest = function(groupId, name) {
    this.groupId = groupId; // long
    this.name = name; // java.lang.String
};

SaveGroupSettingRequest.prototype.protocolId = function() {
    return 18204;
};

SaveGroupSettingRequest.write = function(buffer, packet) {
    if (buffer.writePacketFlag(packet)) {
        return;
    }
    buffer.writeLong(packet.groupId);
    buffer.writeString(packet.name);
};

SaveGroupSettingRequest.read = function(buffer) {
    if (!buffer.readBoolean()) {
        return null;
    }
    const packet = new SaveGroupSettingRequest();
    const result0 = buffer.readLong();
    packet.groupId = result0;
    const result1 = buffer.readString();
    packet.name = result1;
    return packet;
};

export default SaveGroupSettingRequest;
