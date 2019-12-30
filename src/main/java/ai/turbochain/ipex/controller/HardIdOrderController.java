package ai.turbochain.ipex.controller;

import static ai.turbochain.ipex.constant.BooleanEnum.IS_FALSE;
import static ai.turbochain.ipex.constant.BooleanEnum.IS_TRUE;
import static ai.turbochain.ipex.constant.PayMode.ALI;
import static ai.turbochain.ipex.constant.PayMode.BANK;
import static ai.turbochain.ipex.constant.PayMode.WECHAT;
import static ai.turbochain.ipex.constant.SysConstant.API_HARD_ID_MEMBER;
import static ai.turbochain.ipex.util.BigDecimalUtils.add;
import static ai.turbochain.ipex.util.BigDecimalUtils.compare;
import static ai.turbochain.ipex.util.BigDecimalUtils.div;
import static ai.turbochain.ipex.util.BigDecimalUtils.divDown;
import static ai.turbochain.ipex.util.BigDecimalUtils.getRate;
import static ai.turbochain.ipex.util.BigDecimalUtils.isEqual;
import static ai.turbochain.ipex.util.BigDecimalUtils.mulRound;
import static ai.turbochain.ipex.util.BigDecimalUtils.rate;
import static ai.turbochain.ipex.util.BigDecimalUtils.sub;
import static ai.turbochain.ipex.util.MessageResult.success;
import static org.springframework.util.Assert.isTrue;
import static org.springframework.util.Assert.notNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.validation.Valid;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSONObject;
import com.querydsl.core.types.dsl.BooleanExpression;

import ai.turbochain.ipex.coin.CoinExchangeFactory;
import ai.turbochain.ipex.constant.AdvertiseControlStatus;
import ai.turbochain.ipex.constant.AdvertiseType;
import ai.turbochain.ipex.constant.BooleanEnum;
import ai.turbochain.ipex.constant.OrderStatus;
import ai.turbochain.ipex.constant.PriceType;
import ai.turbochain.ipex.constant.TransactionType;
import ai.turbochain.ipex.entity.Advertise;
import ai.turbochain.ipex.entity.Appeal;
import ai.turbochain.ipex.entity.AppealApply;
import ai.turbochain.ipex.entity.Member;
import ai.turbochain.ipex.entity.MemberLegalCurrencyWallet;
import ai.turbochain.ipex.entity.MemberTransaction;
import ai.turbochain.ipex.entity.Order;
import ai.turbochain.ipex.entity.OrderDetail;
import ai.turbochain.ipex.entity.OrderDetailAggregation;
import ai.turbochain.ipex.entity.OrderTypeEnum;
import ai.turbochain.ipex.entity.OtcCoin;
import ai.turbochain.ipex.entity.PayInfo;
import ai.turbochain.ipex.entity.PreOrderInfo;
import ai.turbochain.ipex.entity.QMember;
import ai.turbochain.ipex.entity.ScanOrder;
import ai.turbochain.ipex.entity.WechatPushMessage;
import ai.turbochain.ipex.entity.chat.ChatMessageRecord;
import ai.turbochain.ipex.entity.transform.AuthMember;
import ai.turbochain.ipex.es.ESUtils;
import ai.turbochain.ipex.event.OrderEvent;
import ai.turbochain.ipex.exception.InformationExpiredException;
import ai.turbochain.ipex.pagination.PageResult;
import ai.turbochain.ipex.service.AdvertiseService;
import ai.turbochain.ipex.service.AppealService;
import ai.turbochain.ipex.service.LocaleMessageSourceService;
import ai.turbochain.ipex.service.MemberLegalCurrencyWalletService;
import ai.turbochain.ipex.service.MemberService;
import ai.turbochain.ipex.service.MemberTransactionService;
import ai.turbochain.ipex.service.OrderDetailAggregationService;
import ai.turbochain.ipex.service.OrderService;
import ai.turbochain.ipex.util.BindingResultUtil;
import ai.turbochain.ipex.util.DateUtil;
import ai.turbochain.ipex.util.Md5;
import ai.turbochain.ipex.util.MessageResult;
import ai.turbochain.ipex.vendor.provider.SMSProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * @author
 * @date 2019年12月16日
 */
@RestController
@RequestMapping(value = "/hard-id/order") // , method = RequestMethod.POST
@Slf4j
public class HardIdOrderController {

	@Autowired
	private OrderService orderService;

	@Autowired
	private AdvertiseService advertiseService;

	@Autowired
	private MemberService memberService;

	@Autowired
	private MemberLegalCurrencyWalletService memberLegalCurrencyWalletService;

	@Autowired
	private CoinExchangeFactory coins;

	@Autowired
	private OrderEvent orderEvent;

	@Autowired
	private AppealService appealService;

	@Autowired
	private LocaleMessageSourceService msService;

	@Autowired
	private OrderDetailAggregationService orderDetailAggregationService;

	@Autowired
	private MemberTransactionService memberTransactionService;

	@Value("${spark.system.order.sms:1}")
	private int notice;

	@Autowired
	private SMSProvider smsProvider;

	@Autowired
	private MongoTemplate mongoTemplate;
	@Autowired
	private ESUtils esUtils;

	public static final Integer Advertise_ORDER_TimeLimit = 30;
	public static final String androidView = "com.pansoft.otc.view.OtcNoticeMessageItem";
	public static final String iOSView = "OtcOrderNoticeItem";
	public static final String MSG_Type_Name = "[订单提醒]";
	public static final Integer HARDID_source = 2;
	public static final String TITLE_ORDER = "新订单提醒";
	public static final String TITLE_PAY = "订单支付提醒";
	public static final String CONTENT_PAY = "订单已被标记已付款，请确认收到款后尽快放币。";
	public static final String TITLE_APPEAL = "申诉发起提醒";
	public static final String CONTENT_APPEAL = "您收到一起订单申诉，请点击详情查看。";
	public static final String TITLE_CANCEL = "订单取消提醒";
	public static final String CONTENT_CANCEL = "订单已取消";
	public static final String TITLE_release = "订单完成提醒";
	public static final String CONTENT_release = "您的订单已完成。";
 
	// ImIpex系统Rest地址
	@Value("${im.hardId.ServerUrl}")
	public String imHardIdServerUrl;
	
	@Autowired
	private ExecutorService executorService;

	/**
	 * 买入，卖出详细信息
	 *
	 * @param id
	 * @return
	 */
	@RequestMapping(value = "pre", method = RequestMethod.POST)
	@Transactional(rollbackFor = Exception.class)
	public MessageResult preOrderInfo(long id) {
		Advertise advertise = advertiseService.findOne(id);
		notNull(advertise, msService.getMessage("PARAMETER_ERROR"));
		isTrue(advertise.getStatus().equals(AdvertiseControlStatus.PUT_ON_SHELVES),
				msService.getMessage("PARAMETER_ERROR"));
		Member member = advertise.getMember();
		OtcCoin otcCoin = advertise.getCoin();
		PreOrderInfo preOrderInfo = PreOrderInfo.builder().advertiseType(advertise.getAdvertiseType())
				.country(advertise.getCountry().getZhName())
				.emailVerified(member.getEmail() == null ? IS_FALSE : IS_TRUE)
				.idCardVerified(member.getIdNumber() == null ? IS_FALSE : IS_TRUE).maxLimit(advertise.getMaxLimit())
				.minLimit(advertise.getMinLimit()).number(advertise.getRemainAmount()).otcCoinId(otcCoin.getId())
				.payMode(advertise.getPayMode()).phoneVerified(member.getMobilePhone() == null ? IS_FALSE : IS_TRUE)
				.timeLimit(advertise.getTimeLimit()).transactions(member.getTransactions()).unit(otcCoin.getUnit())
				.username(member.getUsername()).remark(advertise.getRemark()).build();
		// 处理可交易的最大数量
		if (advertise.getAdvertiseType().equals(AdvertiseType.SELL)) {
			BigDecimal maxTransactions = divDown(advertise.getRemainAmount(),
					add(BigDecimal.ONE, getRate(otcCoin.getJyRate())));
			preOrderInfo.setMaxTradableAmount(maxTransactions);
		} else {
			preOrderInfo.setMaxTradableAmount(advertise.getRemainAmount());
		}
		if (advertise.getPriceType().equals(PriceType.REGULAR)) {
			preOrderInfo.setPrice(advertise.getPrice());
		} else {
			BigDecimal marketPrice = coins.get(otcCoin.getUnit());
			preOrderInfo.setPrice(mulRound(marketPrice, rate(advertise.getPremiseRate()), 2));
		}
		MessageResult result = MessageResult.success();
		result.setData(preOrderInfo);
		return result;
	}


	/**
	 * 买币
	 *
	 * @param id
	 * @param coinId
	 * @param price
	 * @param money
	 * @param amount
	 * @param remark
	 * @param user
	 * @return
	 * @throws InformationExpiredException
	 */
	@RequestMapping(value = "buy") // , method = RequestMethod.POST
	@Transactional(rollbackFor = Exception.class)
	public MessageResult buy(Long id, Long coinId, BigDecimal price, BigDecimal money, BigDecimal amount, String remark,
			@RequestParam(value = "mode", defaultValue = "0") Integer mode,
			@SessionAttribute(API_HARD_ID_MEMBER) AuthMember user) throws InformationExpiredException {

		if (id == null) {
			return MessageResult.error("广告ID不能为空");
		}
		if (coinId == null) {
			return MessageResult.error("币种ID不能为空");
		}
		Advertise advertise = advertiseService.findOne(id);
		if (advertise == null) {
			return MessageResult.error("该广告不存在");
		}
		if(!advertise.getAdvertiseType().equals(AdvertiseType.SELL)) {
			return MessageResult.error(msService.getMessage("PARAMETER_ERROR"));
		}
		Member member = memberService.findOne(user.getId());
		user = AuthMember.toAuthMember(member);
		if (user.getRealName() == null) {
			return MessageResult.error("当前用户未完成实名认证");
		}
		isTrue(user.getId() != advertise.getMember().getId(), msService.getMessage("NOT_ALLOW_BUY_BY_SELF"));
		isTrue(advertise.getStatus().equals(AdvertiseControlStatus.PUT_ON_SHELVES),
				msService.getMessage("ALREADY_PUT_OFF"));
		OtcCoin otcCoin = advertise.getCoin();
		if (otcCoin.getId() != coinId) {
			return MessageResult.error(msService.getMessage("PARAMETER_ERROR"));
		}
		if (advertise.getPriceType().equals(PriceType.REGULAR)) {
			isTrue(isEqual(price, advertise.getPrice()), msService.getMessage("PRICE_EXPIRED"));
		} else {
			BigDecimal marketPrice = coins.get(otcCoin.getUnit());
			isTrue(isEqual(price, mulRound(rate(advertise.getPremiseRate()), marketPrice, 2)),
					msService.getMessage("PRICE_EXPIRED"));
		}
		if (mode == 0) {
			isTrue(isEqual(div(money, price), amount), msService.getMessage("NUMBER_ERROR"));
		} else {
			isTrue(isEqual(mulRound(amount, price, 2), money), msService.getMessage("NUMBER_ERROR"));
		}
		isTrue(compare(money, advertise.getMinLimit()),
				msService.getMessage("MONEY_MIN") + advertise.getMinLimit().toString() + " CNY");
		isTrue(compare(advertise.getMaxLimit(), money),
				msService.getMessage("MONEY_MAX") + advertise.getMaxLimit().toString() + " CNY");
		String[] pay = advertise.getPayMode().split(",");
		// 计算手续费
		// if(advertise.getMember().getCertifiedBusinessStatus()==)
		// BigDecimal commission = mulRound(amount, getRate(otcCoin.getJyRate()));
		BigDecimal commission = BigDecimal.ZERO;
		// 认证商家法币交易免手续费
		/**
		 * Member member = memberService.findOne(user.getId());
		 * log.info("会员等级************************************:{},********,{}",member.getCertifiedBusinessStatus(),member.getMemberLevel());
		 * if(member.getCertifiedBusinessStatus().equals(CertifiedBusinessStatus.VERIFIED)
		 * && member.getMemberLevel().equals(MemberLevelEnum.IDENTIFICATION)) {
		 * commission = BigDecimal.ZERO ; }
		 */
		isTrue(compare(advertise.getRemainAmount(), amount), msService.getMessage("AMOUNT_NOT_ENOUGH"));
		Order order = new Order();
		order.setStatus(OrderStatus.NONPAYMENT);
		order.setAdvertiseId(advertise.getId());
		order.setAdvertiseType(advertise.getAdvertiseType());
		order.setCoin(otcCoin);
		order.setCommission(commission);
		order.setCountry(advertise.getCountry().getZhName());
		order.setCustomerId(user.getId());
		order.setCustomerName(user.getName());
		order.setCustomerRealName(member.getRealName());
		order.setMemberId(advertise.getMember().getId());
		order.setMemberName(advertise.getMember().getUsername());
		order.setMemberRealName(advertise.getMember().getRealName());
		order.setMaxLimit(advertise.getMaxLimit());
		order.setMinLimit(advertise.getMinLimit());
		order.setMoney(money);
		order.setNumber(sub(amount, commission));
		order.setPayMode(advertise.getPayMode());
		order.setPrice(price);
		order.setRemark(remark);
		// order.setTimeLimit(advertise.getTimeLimit());
		order.setTimeLimit(Advertise_ORDER_TimeLimit);

		Arrays.stream(pay).forEach(x -> {
			if (ALI.getCnName().equals(x)) {
				order.setAlipay(advertise.getMember().getAlipay());
			} else if (WECHAT.getCnName().equals(x)) {
				order.setWechatPay(advertise.getMember().getWechatPay());
			} else if (BANK.getCnName().equals(x)) {
				order.setBankInfo(advertise.getMember().getBankInfo());
			}
		});
		if (!advertiseService.updateAdvertiseAmountForBuy(advertise.getId(), amount)) {
			throw new InformationExpiredException("Information Expired");
		}
		Order order1 = orderService.saveOrder(order);
		if (order1 != null) {
			if (notice == 1) {
				try {
					// TODO 改为发邮箱
					// smsProvider.sendMessageByTempId(advertise.getMember().getMobilePhone(),
					// advertise.getCoin().getUnit()+"##"+user.getName(),"9499");
				} catch (Exception e) {
					log.error("sms 发送失败");
					e.printStackTrace();
				}
			}
			/**
			 * 下单后，将自动回复记录添加到mongodb
			 */
			if (advertise.getAuto() == BooleanEnum.IS_TRUE) {
				ChatMessageRecord chatMessageRecord = new ChatMessageRecord();
				chatMessageRecord.setOrderId(order1.getOrderSn());
				chatMessageRecord.setUidFrom(order1.getMemberId().toString());
				chatMessageRecord.setUidTo(order1.getCustomerId().toString());
				chatMessageRecord.setNameFrom(order1.getMemberName());
				chatMessageRecord.setNameTo(order1.getCustomerName());
				chatMessageRecord.setContent(advertise.getAutoword());
				chatMessageRecord.setSendTime(Calendar.getInstance().getTimeInMillis());
				chatMessageRecord.setSendTimeStr(DateUtil.getDateTime());
				// 自动回复消息保存到mogondb
				mongoTemplate.insert(chatMessageRecord, "chat_message");
			}
			
			executorService.execute(new Runnable() {
				public void run() {
					sendIM(advertise.getMember().getId(), TITLE_ORDER,"您有一个新的买单，请点击详情查看。", JSONObject.toJSONString(order1));
				}
			});
				
			MessageResult result = MessageResult.success(msService.getMessage("CREATE_ORDER_SUCCESS"));
			result.setData(order1.getOrderSn().toString());
			return result;
		} else {
			throw new InformationExpiredException("Information Expired");
		}
	}

	void sendIM(Long ipexId, String title, String content, String otcOrderBean) {

		WechatPushMessage wechatPushMessage = new WechatPushMessage();

		wechatPushMessage.setAndroidView(androidView);
		wechatPushMessage.setIOSView(iOSView);
		wechatPushMessage.setTitle(title);
		wechatPushMessage.setContent(content);
		wechatPushMessage.setMsgTypeName(MSG_Type_Name);
		wechatPushMessage.setOtcOrderBean(otcOrderBean);

		JSONObject jsonObject = new JSONObject();
		
		jsonObject.put("ipexId", ipexId);
		jsonObject.put("msg", wechatPushMessage);
		jsonObject.put("source", HARDID_source);

		HttpHeaders httpHeaders = new HttpHeaders();
		// 设置请求类型
		httpHeaders.setContentType(MediaType.APPLICATION_JSON_UTF8);
		// 封装参数和头信息
		HttpEntity<JSONObject> httpEntity = new HttpEntity(jsonObject, httpHeaders);
		log.info("push msg"+jsonObject.toJSONString());
		
		RestTemplate restTemplate = new RestTemplate();
		
		// 推送公众号消息
		ResponseEntity<String> mapResponseEntity = restTemplate.postForEntity(imHardIdServerUrl,httpEntity,String.class);
				
		log.info("push Response"+mapResponseEntity.getBody());
	}

	/**
	 * 卖币
	 *
	 * @param id
	 * @param coinId
	 * @param price
	 * @param money
	 * @param amount
	 * @param remark
	 * @param user
	 * @return
	 * @throws InformationExpiredException
	 */
	@RequestMapping(value = "sell")
	@Transactional(rollbackFor = Exception.class)
	public MessageResult sell(Long id, Long coinId, BigDecimal price, BigDecimal money, BigDecimal amount,
			String remark, @RequestParam(value = "mode", defaultValue = "0") Integer mode,
			@SessionAttribute(API_HARD_ID_MEMBER) AuthMember user) throws InformationExpiredException {
		if (id == null) {
			return MessageResult.error("广告ID不能为空");
		}
		if (coinId == null) {
			return MessageResult.error("币种ID不能为空");
		}

		Advertise advertise = advertiseService.findOne(id);
		if (advertise == null || !advertise.getAdvertiseType().equals(AdvertiseType.BUY)) {
			return MessageResult.error(msService.getMessage("PARAMETER_ERROR"));
		}
		isTrue(user.getId() != advertise.getMember().getId(), msService.getMessage("NOT_ALLOW_SELL_BY_SELF"));
		isTrue(advertise.getStatus().equals(AdvertiseControlStatus.PUT_ON_SHELVES),
				msService.getMessage("ALREADY_PUT_OFF"));
		OtcCoin otcCoin = advertise.getCoin();
		if (otcCoin.getId() != coinId) {
			return MessageResult.error(msService.getMessage("PARAMETER_ERROR"));
		}
		if (advertise.getPriceType().equals(PriceType.REGULAR)) {
			isTrue(isEqual(price, advertise.getPrice()), msService.getMessage("PRICE_EXPIRED"));
		} else {
			BigDecimal marketPrice = coins.get(otcCoin.getUnit());
			isTrue(isEqual(price, mulRound(rate(advertise.getPremiseRate()), marketPrice, 2)),
					msService.getMessage("PRICE_EXPIRED"));
		}
		if (mode == 0) {
			isTrue(isEqual(div(money, price), amount), msService.getMessage("NUMBER_ERROR"));
		} else {
			isTrue(isEqual(mulRound(amount, price, 2), money), msService.getMessage("NUMBER_ERROR"));
		}
		isTrue(compare(money, advertise.getMinLimit()),
				msService.getMessage("MONEY_MIN") + advertise.getMinLimit().toString() + " CNY");
		isTrue(compare(advertise.getMaxLimit(), money),
				msService.getMessage("MONEY_MAX") + advertise.getMaxLimit().toString() + " CNY");
		// 计算手续费
		/**
		 * BigDecimal commission = mulRound(amount, getRate(otcCoin.getJyRate()));
		 * log.info("会员等级************************************:{},********,{}",advertise.getMember().getCertifiedBusinessStatus(),advertise.getMember().getMemberLevel());
		 * if(advertise.getMember().getCertifiedBusinessStatus()==CertifiedBusinessStatus.VERIFIED
		 * && advertise.getMember().getMemberLevel()==MemberLevelEnum.IDENTIFICATION) {
		 * commission = BigDecimal.ZERO ; }
		 */
		BigDecimal commission = BigDecimal.ZERO;

		isTrue(compare(advertise.getRemainAmount(), amount), msService.getMessage("AMOUNT_NOT_ENOUGH"));

		MemberLegalCurrencyWallet memberLegalCurrencyWallet = memberLegalCurrencyWalletService
				.findByOtcCoinAndMemberId(otcCoin, user.getId());
		if (memberLegalCurrencyWallet == null) {
			return MessageResult.error("余额不足");
		}

		isTrue(compare(memberLegalCurrencyWallet.getBalance(), amount), msService.getMessage("INSUFFICIENT_BALANCE"));
		Member member = memberService.findOne(user.getId());
		Order order = new Order();
		order.setStatus(OrderStatus.NONPAYMENT);
		order.setAdvertiseId(advertise.getId());
		order.setAdvertiseType(advertise.getAdvertiseType());
		order.setCoin(otcCoin);
		order.setCommission(commission);
		order.setCountry(advertise.getCountry().getZhName());
		order.setCustomerId(user.getId());
		order.setCustomerName(user.getName());
		order.setCustomerRealName(member.getRealName());
		order.setMemberId(advertise.getMember().getId());
		order.setMemberName(advertise.getMember().getUsername());
		order.setMemberRealName(advertise.getMember().getRealName());
		order.setMaxLimit(advertise.getMaxLimit());
		order.setMinLimit(advertise.getMinLimit());
		order.setMoney(money);
		order.setNumber(amount);
		order.setPayMode(advertise.getPayMode());
		order.setPrice(price);
		order.setRemark(remark);
		// order.setTimeLimit(advertise.getTimeLimit());
		order.setTimeLimit(Advertise_ORDER_TimeLimit);

		String[] pay = advertise.getPayMode().split(",");
		MessageResult result = MessageResult.error(msService.getMessage("CREATE_ORDER_SUCCESS"));
		Arrays.stream(pay).forEach(x -> {
			if (ALI.getCnName().equals(x)) {
				if (member.getAlipay() != null) {
					result.setCode(0);
					order.setAlipay(member.getAlipay());
				}
			} else if (WECHAT.getCnName().equals(x)) {
				if (member.getWechatPay() != null) {
					result.setCode(0);
					order.setWechatPay(member.getWechatPay());
				}
			} else if (BANK.getCnName().equals(x)) {
				if (member.getBankInfo() != null) {
					result.setCode(0);
					order.setBankInfo(member.getBankInfo());
				}
			}
		});
		isTrue(result.getCode() == 0, msService.getMessage("AT_LEAST_SUPPORT_PAY"));
		if (!advertiseService.updateAdvertiseAmountForBuy(advertise.getId(), amount)) {
			throw new InformationExpiredException("Information Expired");
		}
		if (!(memberLegalCurrencyWalletService.freezeBalance(memberLegalCurrencyWallet, amount).getCode() == 0)) {
			throw new InformationExpiredException("Information Expired");
		}
		Order order1 = orderService.saveOrder(order);
		if (order1 != null) {
			executorService.execute(new Runnable() {
				public void run() {
					sendIM(advertise.getMember().getId(), TITLE_ORDER,"您有一个新的卖单，请点击详情查看。", JSONObject.toJSONString(order1));
				}
			});
			if (notice == 1) {
				try {
					// smsProvider.sendMessageByTempId(advertise.getMember().getMobilePhone(),
					// advertise.getCoin().getUnit()+"##"+user.getName(),"9499");
				} catch (Exception e) {
					log.error("sms 发送失败");
					e.printStackTrace();
				}
			}
			result.setData(order1.getOrderSn().toString());
			return result;
		} else {
			throw new InformationExpiredException("Information Expired");
		}
	}

	/**
	 * 我的订单
	 *
	 * @param user
	 * @param status
	 * @param pageNo
	 * @param pageSize
	 * @return
	 */
	@RequestMapping(value = "self")
	public MessageResult myOrder(@SessionAttribute(API_HARD_ID_MEMBER) AuthMember user, OrderStatus status, int pageNo,
			int pageSize, String orderSn) {
		Page<Order> page = orderService.pageQuery(pageNo, pageSize, status, user.getId(), orderSn);
		List<Long> memberIdList = new ArrayList<>();
		page.forEach(order -> {
			if (!memberIdList.contains(order.getMemberId())) {
				memberIdList.add(order.getMemberId());
			}
			if (!memberIdList.contains(order.getCustomerId())) {
				memberIdList.add(order.getCustomerId());
			}
		});
		List<BooleanExpression> booleanExpressionList = new ArrayList();
		booleanExpressionList.add(QMember.member.id.in(memberIdList));
		PageResult<Member> memberPage = memberService.queryWhereOrPage(booleanExpressionList, null, null);
		Page<ScanOrder> scanOrders = page.map(x -> ScanOrder.toScanOrder(x, user.getId()));
		for (ScanOrder scanOrder : scanOrders) {
			for (Member member : memberPage.getContent()) {
				if (scanOrder.getMemberId().equals(member.getId())) {
					scanOrder.setAvatar(member.getAvatar());
				}
			}
		}
		MessageResult result = MessageResult.success();
		result.setData(scanOrders);
		return result;
	}

	/**
	 * 订单详情
	 *
	 * @param orderSn
	 * @param user
	 * @return
	 */
	@RequestMapping(value = "detail")
	public MessageResult queryOrder(String orderSn, @SessionAttribute(API_HARD_ID_MEMBER) AuthMember user) {
		Order order = orderService.findOneByOrderSn(orderSn);
		notNull(order, msService.getMessage("ORDER_NOT_EXISTS"));
		MessageResult result = MessageResult.success();
		Member member = memberService.findOne(order.getMemberId());
		OrderDetail info = OrderDetail.builder().orderSn(orderSn).unit(order.getCoin().getUnit())
				.status(order.getStatus()).amount(order.getNumber()).price(order.getPrice()).money(order.getMoney())
				.payTime(order.getPayTime()).createTime(order.getCreateTime()).timeLimit(order.getTimeLimit())
				.myId(user.getId()).memberMobile(member.getMobilePhone()).build();
		/* if (!order.getStatus().equals(OrderStatus.CANCELLED)) { */
		PayInfo payInfo = PayInfo.builder().bankInfo(order.getBankInfo()).alipay(order.getAlipay())
				.wechatPay(order.getWechatPay()).build();
		info.setPayInfo(payInfo);
		/* } */
		if (order.getMemberId().equals(user.getId())) {
			info.setHisId(order.getCustomerId());
			info.setOtherSide(order.getCustomerName());
			info.setCommission(order.getCommission());
			Member memberCustomer = memberService.findOne(order.getCustomerId());
			info.setMemberMobile(memberCustomer.getMobilePhone());
			if (order.getAdvertiseType().equals(AdvertiseType.BUY)) {
				info.setType(AdvertiseType.BUY);
				if (info.getPayInfo() != null) {
					info.getPayInfo().setRealName(order.getCustomerRealName());
				}
			} else {
				info.setType(AdvertiseType.SELL);
				if (info.getPayInfo() != null) {
					info.getPayInfo().setRealName(order.getMemberRealName());
				}
			}
		} else if (order.getCustomerId().equals(user.getId())) {
			info.setHisId(order.getMemberId());
			info.setOtherSide(order.getMemberName());
			info.setCommission(BigDecimal.ZERO);
			Member memberOrder = memberService.findOne(order.getMemberId());
			info.setMemberMobile(memberOrder.getMobilePhone());
			if (order.getAdvertiseType().equals(AdvertiseType.BUY)) {
				if (info.getPayInfo() != null) {
					info.getPayInfo().setRealName(order.getCustomerRealName());
				}
				info.setType(AdvertiseType.SELL);
			} else {
				if (info.getPayInfo() != null) {
					info.getPayInfo().setRealName(order.getMemberRealName());
				}
				info.setType(AdvertiseType.BUY);
			}
		} else {
			return MessageResult.error(msService.getMessage("ORDER_NOT_EXISTS"));
		}
		result.setData(info);
		return result;
	}

	/**
	 * 取消订单
	 *
	 * @param orderSn
	 * @param user
	 * @return
	 */
	@RequestMapping(value = "cancel")
	@Transactional(rollbackFor = Exception.class)
	public MessageResult cancelOrder(String orderSn, @SessionAttribute(API_HARD_ID_MEMBER) AuthMember user)
			throws InformationExpiredException {
		Order order = orderService.findOneByOrderSn(orderSn);
		notNull(order, msService.getMessage("ORDER_NOT_EXISTS"));
		int ret = 0;Long otherUserId = null;
		if (order.getAdvertiseType().equals(AdvertiseType.BUY) && order.getMemberId().equals(user.getId())) {
			// 代表该会员是广告发布者，购买类型的广告，并且是付款者
			ret = 1;otherUserId = order.getCustomerId();
		} else if (order.getAdvertiseType().equals(AdvertiseType.SELL) && order.getCustomerId().equals(user.getId())) {
			// 代表该会员不是广告发布者，并且是付款者
			ret = 2;otherUserId = order.getMemberId();
		}
		isTrue(ret != 0, msService.getMessage("REQUEST_ILLEGAL"));
		isTrue(order.getStatus().equals(OrderStatus.NONPAYMENT) || order.getStatus().equals(OrderStatus.PAID),
				msService.getMessage("ORDER_NOT_ALLOW_CANCEL"));
		MemberLegalCurrencyWallet memberLegalCurrencyWallet;
		// 取消订单
		if (!(orderService.cancelOrder(order.getOrderSn()) > 0)) {
			throw new InformationExpiredException("Information Expired");
		}
		order.setStatus(OrderStatus.CANCELLED);
		
		if (ret == 1) {
			// 更改广告
			// 创建订单的时候减少了realAmount，增加了dealAmount，撤销时只减少了dealAmount的金额，没有增加realAmount的金额
			if (!advertiseService.updateAdvertiseAmountForCancel(order.getAdvertiseId(), order.getNumber())) {
				throw new InformationExpiredException("Information Expired");
			}
			memberLegalCurrencyWallet = memberLegalCurrencyWalletService.findByOtcCoinAndMemberId(order.getCoin(),
					order.getCustomerId());
			MessageResult result = memberLegalCurrencyWalletService.thawBalance(memberLegalCurrencyWallet,
					order.getNumber());
			if (result.getCode() == 0) {
				final Long sendUserId = otherUserId;
				executorService.execute(new Runnable() {
					public void run() {
						sendIM(sendUserId, TITLE_CANCEL,CONTENT_CANCEL, JSONObject.toJSONString(order));
					}
				});
				return MessageResult.success(msService.getMessage("CANCEL_SUCCESS"));
			} else {
				throw new InformationExpiredException("Information Expired");
			}
		} else {
			// 更改广告
			if (!advertiseService.updateAdvertiseAmountForCancel(order.getAdvertiseId(),
					add(order.getNumber(), order.getCommission()))) {
				throw new InformationExpiredException("Information Expired");
			}
			
			memberLegalCurrencyWallet = memberLegalCurrencyWalletService.findByOtcCoinAndMemberId(order.getCoin(),
					order.getMemberId());
			MessageResult result = memberLegalCurrencyWalletService.thawBalance(memberLegalCurrencyWallet,
					add(order.getNumber(), order.getCommission()));
			if (result.getCode() == 0) {
				final Long sendUserId = otherUserId;
				executorService.execute(new Runnable() {
					public void run() {
						sendIM(sendUserId, TITLE_CANCEL,CONTENT_CANCEL, JSONObject.toJSONString(order));
					}
				});
				
				return MessageResult.success(msService.getMessage("CANCEL_SUCCESS"));
			} else {
				throw new InformationExpiredException("Information Expired");
			}
		}
	}

	/**
	 * 确认付款
	 *
	 * @param orderSn
	 * @param user
	 * @return
	 */
	@RequestMapping(value = "pay")
	@Transactional(rollbackFor = Exception.class)
	public MessageResult payOrder(String orderSn, Integer payType,@SessionAttribute(API_HARD_ID_MEMBER) AuthMember user)
			throws InformationExpiredException {
		if (payType == null) {
			return MessageResult.error("请设置付款方式");
		}

		Order order = orderService.findOneByOrderSn(orderSn);
		notNull(order, msService.getMessage("ORDER_NOT_EXISTS"));
		int ret = 0;Long otherUserId = null;
		if (order.getAdvertiseType().equals(AdvertiseType.BUY) && order.getMemberId().equals(user.getId())) {
			// 代表该会员是广告发布者，并且是付款者
			ret = 1;otherUserId=order.getCustomerId();
		} else if (order.getAdvertiseType().equals(AdvertiseType.SELL) && order.getCustomerId().equals(user.getId())) {
			// 代表该会员不是广告发布者
			ret = 2;otherUserId=order.getMemberId();
		}
		isTrue(ret != 0, msService.getMessage("REQUEST_ILLEGAL"));
		isTrue(order.getStatus().equals(OrderStatus.NONPAYMENT), msService.getMessage("ORDER_STATUS_EXPIRED"));
		isTrue(compare(new BigDecimal(order.getTimeLimit()), DateUtil.diffMinute(order.getCreateTime())),
				msService.getMessage("ORDER_ALREADY_AUTO_CANCEL"));
		
		int is = orderService.payForOrder(orderSn);
		order.setStatus(OrderStatus.PAID);
		order.setPayType(payType);
		
		if (is > 0) {
			/**
			 * 聚合otc订单手续费等明细存入mongodb
			 */
			OrderDetailAggregation aggregation = new OrderDetailAggregation();
			BeanUtils.copyProperties(order, aggregation);
			aggregation.setUnit(order.getCoin().getUnit());
			aggregation.setOrderId(order.getOrderSn());
			aggregation.setFee(order.getCommission().doubleValue());
			aggregation.setAmount(order.getNumber().doubleValue());
			aggregation.setType(OrderTypeEnum.OTC);
			aggregation.setTime(Calendar.getInstance().getTimeInMillis());
			
			orderDetailAggregationService.save(aggregation);
			final Long sendUserId = otherUserId;
			executorService.execute(new Runnable() {
				public void run() {
					sendIM(sendUserId, TITLE_PAY,CONTENT_PAY, JSONObject.toJSONString(order));
				}
			});
			
			MessageResult result = MessageResult.success(msService.getMessage("PAY_SUCCESS"));
			result.setData(order);
			return result;
		} else {
			throw new InformationExpiredException("Information Expired");
		}

	}

	/**
	 * 订单放行
	 *
	 * @param orderSn
	 * @param user
	 * @return
	 */
	@RequestMapping(value = "release")
	@Transactional(rollbackFor = Exception.class)
	public MessageResult confirmRelease(String orderSn, String jyPassword,
			@SessionAttribute(API_HARD_ID_MEMBER) AuthMember user) throws Exception {
		Assert.hasText(jyPassword, msService.getMessage("MISSING_JYPASSWORD"));
		Member member = memberService.findOne(user.getId());
		String mbPassword = member.getJyPassword();
		Assert.hasText(mbPassword, msService.getMessage("NO_SET_JYPASSWORD"));
		Assert.isTrue(Md5.md5Digest(jyPassword + member.getSalt()).toLowerCase().equals(mbPassword),
				msService.getMessage("ERROR_JYPASSWORD"));
		Order order = orderService.findOneByOrderSn(orderSn);
		notNull(order, msService.getMessage("ORDER_NOT_EXISTS"));
		int ret = 0;Long otherUserId = null;
		if (order.getAdvertiseType().equals(AdvertiseType.BUY) && order.getCustomerId().equals(user.getId())) {
			// 代表该会员不是广告发布者，并且是放行者
			ret = 1;otherUserId=order.getMemberId();
		} else if (order.getAdvertiseType().equals(AdvertiseType.SELL) && order.getMemberId().equals(user.getId())) {
			// 代表该会员是广告发布者，并且是放行者
			ret = 2;otherUserId=order.getCustomerId();
		}
		isTrue(ret != 0, msService.getMessage("REQUEST_ILLEGAL"));
		isTrue(order.getStatus().equals(OrderStatus.PAID), msService.getMessage("ORDER_STATUS_EXPIRED"));
		if (ret == 1) {
			// 更改广告
			if (!advertiseService.updateAdvertiseAmountForRelease(order.getAdvertiseId(), order.getNumber())) {
				throw new InformationExpiredException("Information Expired");
			}
		} else {
			// 更改广告
			if (!advertiseService.updateAdvertiseAmountForRelease(order.getAdvertiseId(),
					add(order.getNumber(), order.getCommission()))) {
				throw new InformationExpiredException("Information Expired");
			}
		}
		// 放行订单
		if (!(orderService.releaseOrder(order.getOrderSn()) > 0)) {
			throw new InformationExpiredException("Information Expired");
		}
		order.setStatus(OrderStatus.COMPLETED);
		// 更改钱包
		memberLegalCurrencyWalletService.transfer(order, ret);

		// return MessageResult.error("放币失败,请检查钱包剩余币值");

		MemberTransaction memberTransaction = new MemberTransaction();
		MemberTransaction memberTransaction1 = new MemberTransaction();

		if (ret == 1) {
			memberTransaction.setSymbol(order.getCoin().getUnit());
			memberTransaction.setType(TransactionType.OTC_SELL);
			memberTransaction.setFee(BigDecimal.ZERO);
			memberTransaction.setMemberId(user.getId());
			memberTransaction.setAmount(order.getNumber());
			memberTransaction.setDiscountFee("0");
			memberTransaction.setRealFee("0");
			memberTransaction.setCreateTime(new Date());
			memberTransaction = memberTransactionService.save(memberTransaction);

			memberTransaction1.setAmount(order.getNumber());
			memberTransaction1.setType(TransactionType.OTC_BUY);
			memberTransaction1.setMemberId(order.getMemberId());
			memberTransaction1.setSymbol(order.getCoin().getUnit());
			memberTransaction1.setFee(order.getCommission());
			memberTransaction1.setDiscountFee("0");
			memberTransaction1.setRealFee(order.getCommission() + "");
			memberTransaction1.setCreateTime(new Date());
			memberTransaction1 = memberTransactionService.save(memberTransaction1);
		} else {
			memberTransaction.setSymbol(order.getCoin().getUnit());
			memberTransaction.setType(TransactionType.OTC_SELL);
			memberTransaction.setFee(order.getCommission());
			memberTransaction.setMemberId(user.getId());
			memberTransaction.setAmount(order.getNumber());
			memberTransaction.setDiscountFee("0");
			memberTransaction.setRealFee(order.getCommission() + "");
			memberTransaction.setCreateTime(new Date());
			memberTransaction = memberTransactionService.save(memberTransaction);

			memberTransaction1.setAmount(order.getNumber());
			memberTransaction1.setType(TransactionType.OTC_BUY);
			memberTransaction1.setMemberId(order.getCustomerId());
			memberTransaction1.setSymbol(order.getCoin().getUnit());
			memberTransaction1.setFee(BigDecimal.ZERO);

			memberTransaction1.setDiscountFee("0");
			memberTransaction1.setRealFee(order.getCommission() + "");
			memberTransaction1.setCreateTime(new Date());
			memberTransaction1 = memberTransactionService.save(memberTransaction1);
		}
		orderEvent.onOrderCompleted(order);
		
		final Long sendUserId = otherUserId;
		
		executorService.execute(new Runnable() {
			public void run() {
				sendIM(sendUserId, TITLE_release,"您的订单"+order.getOrderSn()+"已完成。", JSONObject.toJSONString(order));
			}
		});
		
		return MessageResult.success(msService.getMessage("RELEASE_SUCCESS"));
	}

	/**
	 * 申诉
	 *
	 * @param appealApply
	 * @param bindingResult
	 * @param user
	 * @return
	 * @throws InformationExpiredException
	 */
	@RequestMapping(value = "appeal")
	@Transactional(rollbackFor = Exception.class)
	public MessageResult appeal(@Valid AppealApply appealApply, BindingResult bindingResult,
			@SessionAttribute(API_HARD_ID_MEMBER) AuthMember user) throws InformationExpiredException {
		MessageResult result = BindingResultUtil.validate(bindingResult);
		if (result != null) {
			return result;
		}
		Order order = orderService.findOneByOrderSn(appealApply.getOrderSn());
		Long sendUserId = null;
		int ret = 0;
		if (order.getMemberId().equals(user.getId())) {
			ret = 1;sendUserId =order.getCustomerId();
		} else if (order.getCustomerId().equals(user.getId())) {
			ret = 2;sendUserId =order.getMemberId();
		}
		isTrue(ret != 0, msService.getMessage("REQUEST_ILLEGAL"));
		isTrue(order.getStatus().equals(OrderStatus.PAID), msService.getMessage("NO_APPEAL"));
		if (!(orderService.updateOrderAppeal(order.getOrderSn()) > 0)) {
			throw new InformationExpiredException("Information Expired");
		}
		order.setStatus(OrderStatus.APPEAL);
		Appeal appeal = new Appeal();
		appeal.setInitiatorId(user.getId());
		if (ret == 1) {
			appeal.setAssociateId(order.getCustomerId());
		} else {
			appeal.setAssociateId(order.getMemberId());
		}
		String[] image = appealApply.getImage();
		if (image != null) {
			appeal.setImages(ArrayUtils.toString(image, ","));
			;
		}
		appeal.setOrder(order);
		appeal.setRemark(appealApply.getRemark());
		Appeal appeal1 = appealService.save(appeal);
		if (appeal1 != null) {
			final Long otherUserId = sendUserId;
			executorService.execute(new Runnable() {
				public void run() {
					sendIM(otherUserId, TITLE_APPEAL,CONTENT_APPEAL, JSONObject.toJSONString(order));
				}
			});
			
			return MessageResult.success(msService.getMessage("APPEAL_SUCCESS"));
		} else {
			throw new InformationExpiredException("Information Expired");
		}
	}

	/**
	 * 申诉内容详情
	 *
	 * @param appealApply
	 * @param bindingResult
	 * @param user
	 * @return
	 * @throws InformationExpiredException
	 */
	@RequestMapping(value = "/appeal/detail")
	@Transactional(rollbackFor = Exception.class)
	public MessageResult appealDetail(String orderSn, @SessionAttribute(API_HARD_ID_MEMBER) AuthMember user)
			throws InformationExpiredException {

		if (StringUtils.isBlank(orderSn)) {
			return MessageResult.error("订单号不能为空");
		}

		Order order = orderService.findOneByOrderSn(orderSn);
		if (order == null) {
			return MessageResult.error("该订单已被删除");
		}

		List<Appeal> appealList = appealService.findByOrder(order);
		List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		for (Appeal appeal : appealList) {
			Map<String, Object> map = new HashMap<String, Object>();

			/**
			 * 申诉发起者id
			 */
			Long initiatorId = appeal.getInitiatorId();
			Member initiatorMember = memberService.findOne(initiatorId);

			map.put("initiatorMember", initiatorMember);

			/**
			 * 申诉关联者id
			 */
			Long associateId = appeal.getAssociateId();

			Member associateMember = memberService.findOne(associateId);

			map.put("associateMember", associateMember);
			map.put("id", appeal.getId());
			map.put("remark", appeal.getRemark());
			map.put("createTime", appeal.getCreateTime());
			map.put("dealWithTime", appeal.getDealWithTime());
			map.put("images", appeal.getImages());
			map.put("status", appeal.getStatus());
			map.put("isSuccess", appeal.getIsSuccess());

			list.add(map);
		}

		MessageResult result = success();

		result.setData(list);

		return result;
	}
 
}
