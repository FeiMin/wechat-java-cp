package com.sdk.wx.cp.controller;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.sdk.wx.cp.api.AppAuthApi;
import com.sdk.wx.cp.api.WechatCommonApi;
import com.sdk.wx.cp.api.impl.AppAuthApiImpl;
import com.sdk.wx.cp.bean.GetPermanentCodeResult;
import com.sdk.wx.cp.common.ende.AesException;
import com.sdk.wx.cp.common.ende.WXBizMsgCrypt;
import com.sdk.wx.cp.enums.InfoTypeConstants;
import com.sdk.wx.cp.enums.MsgTypeConstants;
import com.sdk.wx.cp.hander.dto.WechatMessageIn;
import com.sdk.wx.cp.storage.InMemoryConfigStorage;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;

/**
 * 指令回调地址处理（该类用于测试消息与事件回调解析）
 * @author yangtao
 * @date 2019/05/29
 */
@RestController
@RequestMapping("/wx/cp/auth/{suiteId}")
@Slf4j
public class InstructCallbackController {

	@Autowired
	private WechatCommonApi wechatCommonApi;

	@Autowired
	private InMemoryConfigStorage inMemoryConfigStorage;

	/**
	 * 此处用于微信服务器的回调URL响应处理
	 * @param signature
	 * @param timestamp
	 * @param nonce
	 * @param echostr
	 * @return
	 * @throws AesException
	 */
	@GetMapping(produces = "text/plain;charset=utf-8")
	public String authGet(@PathVariable String suiteId,
			@RequestParam(name = "msg_signature", required = false) String signature,
			@RequestParam(name = "timestamp", required = false) String timestamp,
			@RequestParam(name = "nonce", required = false) String nonce,
			@RequestParam(name = "echostr", required = false) String echostr) throws AesException {
		log.info("\n接收到来自微信服务器的认证消息：signature = [{}], timestamp = [{}], nonce = [{}], echostr = [{}]",
				signature, timestamp, nonce, echostr);
		if (StringUtils.isAnyBlank(signature, timestamp, nonce, echostr)) {
			throw new IllegalArgumentException("请求参数非法，请核实!");
		}
		//校验url前，初始化token管理器
		wechatCommonApi.initStorage(inMemoryConfigStorage);
		//设置token及配置信息到加密工具类
		WXBizMsgCrypt wxcpt = new WXBizMsgCrypt(wechatCommonApi.getConfigStorage().getToken(suiteId), 
				wechatCommonApi.getConfigStorage().getAesKey(suiteId), 
				wechatCommonApi.getConfigStorage().getCorpid(),
				new String[]{wechatCommonApi.getConfigStorage().getCorpid(),suiteId});
		//校验url
		String result = wxcpt.VerifyURL(signature, timestamp,
				nonce, echostr);
		log.info("返回校验结果数据："+result);
		return result;
	}

	/**
	 * 此方法用于消息事件回调解析
	 * @param requestBody
	 * @param signature
	 * @param timestamp
	 * @param nonce
	 * @return
	 * @throws AesException
	 */
	@PostMapping(produces = "application/xml; charset=UTF-8")
	public String post(@PathVariable String suiteId,
			@RequestBody String requestBody,
			@RequestParam("msg_signature") String signature, 
			@RequestParam("timestamp") String timestamp,
			@RequestParam("nonce") String nonce) throws AesException {
		log.info(
				"\n接收微信请求：[requestBody=[{}], msg_signature=[{}], timestamp=[{}], nonce=[{}] ",
				requestBody, signature, timestamp, nonce);
		try{
			/**
			 * 初始化token管理器，sdk使用基于内存的token管理器。
			 * 上层应用可以集成token管理器接口，实现自己的token管理器（基于redis,db,memorycache，做token分布式实现等）
			 */
			wechatCommonApi.initStorage(inMemoryConfigStorage);
		}catch (Exception e) {
			e.printStackTrace();
		}
		WechatMessageIn inMessage = WechatMessageIn.fromEncryptedXml(suiteId, requestBody, wechatCommonApi.getConfigStorage(),
				timestamp, nonce, signature);
		log.info("\n消息解密后内容为：\n{} ", JSONUtil.toJsonStr(inMessage));
		
		/*
		 * 事件消息解析；此处为测试代码，后续会交给处理器执行
		 */
		dealMessage(suiteId, inMessage);
		
		return "success";
	}
	
	/**
	 * 处理回调信息
	 * @param inMessage
	 */
	public void dealMessage(String suiteId, WechatMessageIn inMessage){
		try {
			CompletableFuture.runAsync(()->{
				try{
					if(StringUtils.isNotBlank(inMessage.getInfoType())){
						switch(inMessage.getInfoType()){
							case InfoTypeConstants.SUITE_TICKET:
								log.info("推送suite_ticket事件，打印suite_ticket信息:"+inMessage.getSuiteTicket());
								wechatCommonApi.getConfigStorage().setSuiteTicket(suiteId, inMessage.getSuiteTicket());
								log.info("保存suite_ticket成功，开始获取suite_access_token:"+wechatCommonApi.getSuiteAccessToken(suiteId));
								log.info("当前的storage信息："+ JSONUtil.toJsonStr(wechatCommonApi.getConfigStorage()));
								break;
							case InfoTypeConstants.CREATE_AUTH:
								log.info("授权成功通知事件，记录auth_info信息："+inMessage.getAuthCode());
								AppAuthApi appAuthPai = new AppAuthApiImpl(wechatCommonApi);
								GetPermanentCodeResult perResult = appAuthPai.getPermanentCode(suiteId, inMessage.getAuthCode());
								log.info("获取企业永久授权码返回的完整信息："+JSONUtil.toJsonStr(perResult));
								wechatCommonApi.getConfigStorage().setPermanentCode(suiteId, perResult.getAuthCorpInfo().getCorpid(), perResult);
								log.info("打印storage信息："+ JSONUtil.toJsonStr(wechatCommonApi.getConfigStorage().getPermanentInfo(suiteId, perResult.getAuthCorpInfo().getCorpid())));
							case InfoTypeConstants.CHANGE_AUTH:
								log.info("授权变更事件，请重新查询授权信息");
								break;
							case InfoTypeConstants.CANCEL_AUTH:
								log.info("取消授权事件，请确认取消授权的用户相关信息");
								break;
							case InfoTypeConstants.CHANGE_CONTACT:
								log.info("联系人变化事件，请查看回调信息");
								break;
							case InfoTypeConstants.REGISTER_CORP:
								log.info("注册完成回调事件，请查看回调信息");
								break;
							case InfoTypeConstants.CHANGE_EXTERNAL_CONTACT:
								log.info("外部联系人变更回调，请查看回调信息");
								break;
							default:
								log.info("未找到匹配的回调类型，请对应官方文档查看回调信息");
								
						}
					}else if(StringUtils.isNotBlank(inMessage.getMsgType())){
						switch(inMessage.getMsgType()){
							case MsgTypeConstants.EVENT:
								log.info("事件通知类型，请查看详细回调信息");
								break;
							case MsgTypeConstants.TEXT:
								log.info("文本通知类型，请查看详细回调信息");
								break;
							case MsgTypeConstants.IMAGE:
								log.info("图片通知类型，请查看详细回调信息");
								break;
							case MsgTypeConstants.VOICE:
								log.info("语言通知类型，请查看详细回调信息");
								break;
							case MsgTypeConstants.VIDEO:
								log.info("视频通知类型，请查看详细回调信息");
								break;
							case MsgTypeConstants.LOCATION:
								log.info("位置通知类型，请查看详细回调信息");
								break;
							case MsgTypeConstants.LINK:
								log.info("链接通知类型，请查看详细回调信息");
								break;
							default :
								log.info("未找到匹配的消息类型，请对应官方文档查看回调信息");
						}
					}
				}catch (WxErrorException e) {
					log.error(e.getMessage(),e);
				}
				
			}).get();
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
	}
	

}
