package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.response.cmd;

import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.event.DeviceOffLineDetector;
import com.genersoft.iot.vmp.gb28181.event.EventPublisher;
import com.genersoft.iot.vmp.gb28181.session.CatalogDataCatch;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.RequestMessage;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.SIPRequestProcessorParent;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.IMessageHandler;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.response.ResponseMessageHandler;
import com.genersoft.iot.vmp.gb28181.utils.NumericUtil;
import com.genersoft.iot.vmp.gb28181.utils.XmlUtil;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.IVideoManagerStorage;
import com.genersoft.iot.vmp.utils.GpsUtil;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.SipException;
import javax.sip.message.Response;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Component
public class CatalogResponseMessageHandler extends SIPRequestProcessorParent implements InitializingBean, IMessageHandler {

    private Logger logger = LoggerFactory.getLogger(CatalogResponseMessageHandler.class);
    private final String cmdType = "Catalog";

    private SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ResponseMessageHandler responseMessageHandler;

    @Autowired
    private IVideoManagerStorage storager;

    @Autowired
    private DeferredResultHolder deferredResultHolder;

    @Autowired
    private CatalogDataCatch catalogDataCatch;

    @Autowired
    private DeviceOffLineDetector offLineDetector;

    @Autowired
    private SipConfig config;

    @Autowired
    private EventPublisher publisher;

    //by brewswang
    @Autowired
    private UserSetting userSetting;

    @Autowired
    private IRedisCatchStorage redisCatchStorage;

    @Override
    public void afterPropertiesSet() throws Exception {
        responseMessageHandler.addHandler(cmdType, this);
    }

    @Override
    public void handForDevice(RequestEvent evt, Device device, Element element) {
        String key = DeferredResultHolder.CALLBACK_CMD_CATALOG + device.getDeviceId();
        Element rootElement = null;
        try {
            rootElement = getRootElement(evt, device.getCharset());
            Element deviceListElement = rootElement.element("DeviceList");
            Element sumNumElement = rootElement.element("SumNum");
            if (sumNumElement == null || deviceListElement == null) {
                responseAck(evt, Response.BAD_REQUEST, "xml error");
                return;
            }
            int sumNum = Integer.parseInt(sumNumElement.getText());
            if (sumNum == 0) {
                // 数据已经完整接收
                storager.cleanChannelsForDevice(device.getDeviceId());
                RequestMessage msg = new RequestMessage();
                msg.setKey(key);
                WVPResult<Object> result = new WVPResult<>();
                result.setCode(0);
                result.setData(device);
                msg.setData(result);
                result.setMsg("更新成功，共0条");
                deferredResultHolder.invokeAllResult(msg);
                catalogDataCatch.del(key);
            }else {
                Iterator<Element> deviceListIterator = deviceListElement.elementIterator();
                if (deviceListIterator != null) {
                    List<DeviceChannel> channelList = new ArrayList<>();
                    // 遍历DeviceList
                    while (deviceListIterator.hasNext()) {
                        Element itemDevice = deviceListIterator.next();
                        Element channelDeviceElement = itemDevice.element("DeviceID");
                        if (channelDeviceElement == null) {
                            continue;
                        }
                        //by brewswang
//                        if (NumericUtil.isDouble(XmlUtil.getText(itemDevice, "Longitude"))) {//如果包含位置信息，就更新一下位置
//                            processNotifyMobilePosition(evt, itemDevice);
//                        }
                        DeviceChannel deviceChannel = XmlUtil.channelContentHander(itemDevice);
                        deviceChannel.setDeviceId(device.getDeviceId());

                        channelList.add(deviceChannel);
                    }
                    logger.info("收到来自设备【{}】的通道: {}个，{}/{}", device.getDeviceId(), channelList.size(), catalogDataCatch.get(key) == null ? 0 :catalogDataCatch.get(key).size(), sumNum);
                    catalogDataCatch.put(key, sumNum, device, channelList);
                    if (catalogDataCatch.get(key).size() == sumNum) {
                        // 数据已经完整接收
                        boolean resetChannelsResult = storager.resetChannels(device.getDeviceId(), catalogDataCatch.get(key));
                        RequestMessage msg = new RequestMessage();
                        msg.setKey(key);
                        WVPResult<Object> result = new WVPResult<>();
                        result.setCode(0);
                        result.setData(device);
                        if (resetChannelsResult || sumNum ==0) {
                            result.setMsg("更新成功，共" + sumNum + "条，已更新" + catalogDataCatch.get(key).size() + "条");
                        }else {
                            result.setMsg("接收成功，写入失败，共" + sumNum + "条，已接收" + catalogDataCatch.get(key).size() + "条");
                        }
                        msg.setData(result);
                        deferredResultHolder.invokeAllResult(msg);
                        catalogDataCatch.del(key);
                    }
                }
                // 回复200 OK
                responseAck(evt, Response.OK);
                if (offLineDetector.isOnline(device.getDeviceId())) {
                    publisher.onlineEventPublish(device, VideoManagerConstants.EVENT_ONLINE_MESSAGE);
                }
            }
        } catch (DocumentException e) {
            e.printStackTrace();
        } catch (InvalidArgumentException e) {
            e.printStackTrace();
        } catch (ParseException e) {
            e.printStackTrace();
        } catch (SipException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void handForPlatform(RequestEvent evt, ParentPlatform parentPlatform, Element rootElement) {

    }

    /**
     * 处理设备位置的更新
     *
     * @param evt, itemDevice
     */
    private void processNotifyMobilePosition(RequestEvent evt, Element itemDevice) {
        try {
            // 回复 200 OK
            Element rootElement = getRootElement(evt);
            MobilePosition mobilePosition = new MobilePosition();
            Element deviceIdElement = rootElement.element("DeviceID");
            String deviceId = deviceIdElement.getTextTrim().toString();
            Device device = redisCatchStorage.getDevice(deviceId);
            if (device != null) {
                if (!StringUtils.isEmpty(device.getName())) {
                    mobilePosition.setDeviceName(device.getName());
                }
            }
            mobilePosition.setDeviceId(XmlUtil.getText(rootElement, "DeviceID"));

            String time = XmlUtil.getText(itemDevice, "Time");
            if(time==null){
                time =  XmlUtil.getText(itemDevice, "EndTime");
            }
            mobilePosition.setTime(time);
            String longitude = XmlUtil.getText(itemDevice, "Longitude");
            if(longitude!=null) {
                mobilePosition.setLongitude(Double.parseDouble(longitude));
            }
            String latitude = XmlUtil.getText(itemDevice, "Latitude");
            if(latitude!=null) {
                mobilePosition.setLatitude(Double.parseDouble(latitude));
            }
            if (NumericUtil.isDouble(XmlUtil.getText(itemDevice, "Speed"))) {
                mobilePosition.setSpeed(Double.parseDouble(XmlUtil.getText(itemDevice, "Speed")));
            } else {
                mobilePosition.setSpeed(0.0);
            }
            if (NumericUtil.isDouble(XmlUtil.getText(itemDevice, "Direction"))) {
                mobilePosition.setDirection(Double.parseDouble(XmlUtil.getText(itemDevice, "Direction")));
            } else {
                mobilePosition.setDirection(0.0);
            }
            if (NumericUtil.isDouble(XmlUtil.getText(itemDevice, "Altitude"))) {
                mobilePosition.setAltitude(Double.parseDouble(XmlUtil.getText(itemDevice, "Altitude")));
            } else {
                mobilePosition.setAltitude(0.0);
            }
            mobilePosition.setReportSource("Mobile Position");
            BaiduPoint bp = new BaiduPoint();
            bp = GpsUtil.Wgs84ToBd09(String.valueOf(mobilePosition.getLongitude()), String.valueOf(mobilePosition.getLatitude()));
            logger.info("百度坐标：" + bp.getBdLng() + ", " + bp.getBdLat());
            mobilePosition.setGeodeticSystem("BD-09");
            mobilePosition.setCnLng(bp.getBdLng());
            mobilePosition.setCnLat(bp.getBdLat());
            if (!userSetting.getSavePositionHistory()) {
                storager.clearMobilePositionsByDeviceId(deviceId);
            }
            storager.insertMobilePosition(mobilePosition);
            responseAck(evt, Response.OK);
        } catch (DocumentException | SipException | InvalidArgumentException | ParseException e) {
            e.printStackTrace();
        }
    }

    public SyncStatus getChannelSyncProgress(String deviceId) {
        String key = DeferredResultHolder.CALLBACK_CMD_CATALOG + deviceId;
        if (catalogDataCatch.get(key) == null) {
            return null;
        }else {
            return catalogDataCatch.getSyncStatus(key);
        }
    }

    public void setChannelSyncReady(String deviceId) {
        String key = DeferredResultHolder.CALLBACK_CMD_CATALOG + deviceId;
        catalogDataCatch.addReady(key);
    }

    public void setChannelSyncEnd(String deviceId, String errorMsg) {
        String key = DeferredResultHolder.CALLBACK_CMD_CATALOG + deviceId;
        catalogDataCatch.setChannelSyncEnd(key, errorMsg);
    }
}
