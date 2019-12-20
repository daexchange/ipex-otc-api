package ai.turbochain.ipex.controller;

import static ai.turbochain.ipex.constant.SysConstant.API_HARD_ID_MEMBER;
import static ai.turbochain.ipex.constant.SysConstant.SESSION_MEMBER;
import static org.springframework.util.Assert.notNull;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import ai.turbochain.ipex.constant.*;
import com.alibaba.druid.sql.ast.statement.SQLIfStatement;
import org.apache.catalina.servlet4preview.http.HttpServletRequest;
import org.apache.poi.util.SystemOutLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.sparkframework.sql.DataException;

import ai.turbochain.ipex.coin.CoinExchangeFactory;
import ai.turbochain.ipex.entity.Advertise;
import ai.turbochain.ipex.entity.Member;
import ai.turbochain.ipex.entity.Order;
import ai.turbochain.ipex.entity.OrderDetail;
import ai.turbochain.ipex.entity.OtcCoin;
import ai.turbochain.ipex.entity.PayInfo;
import ai.turbochain.ipex.entity.QMember;
import ai.turbochain.ipex.entity.RespDetail;
import ai.turbochain.ipex.entity.ScanOrder;
import ai.turbochain.ipex.entity.transform.AuthMember;
import ai.turbochain.ipex.entity.transform.MemberAdvertiseInfo;
import ai.turbochain.ipex.entity.transform.ScanAdvertise;
import ai.turbochain.ipex.entity.transform.SpecialPage;
import ai.turbochain.ipex.pagination.PageResult;
import ai.turbochain.ipex.service.AdvertiseService;
import ai.turbochain.ipex.service.LocaleMessageSourceService;
import ai.turbochain.ipex.service.MemberService;
import ai.turbochain.ipex.service.OrderService;
import ai.turbochain.ipex.service.OtcCoinService;
import ai.turbochain.ipex.util.MessageResult;

/**
 * @author 未央
 * @create 2019-12-13 9:25
 */
@RestController
@RequestMapping("/otc-adver")
public class OtcAdvController extends BaseController {

    @Autowired
    private AdvertiseService advertiseService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private OtcCoinService otcCoinService;

    @Autowired
    private LocaleMessageSourceService msService;

    @Autowired
    private CoinExchangeFactory coins;

    /**
     * 法币交易广告查询
     *
     * @param pageNo
     * @param pageSize
     * @param unit
     * @param advertiseType
     * @param isCertified
     * @return
     * @throws SQLException
     * @throws DataException
     */
    @RequestMapping(value = "page-by-unit")
    public MessageResult queryPageAdvertiseByUnit(@RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                                  @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
                                                  String unit, AdvertiseType advertiseType,
                                                  @RequestParam(value = "isCertified", defaultValue = "0") Integer isCertified) throws SQLException, DataException {
        OtcCoin otcCoin = otcCoinService.findByUnit(unit);
        Assert.notNull(otcCoin, "validate otcCoin unit!");
        double marketPrice = coins.get(otcCoin.getUnit()).doubleValue();
        SpecialPage<ScanAdvertise> page = advertiseService.paginationAdvertise(pageNo, pageSize, otcCoin, advertiseType, marketPrice, isCertified);
        MessageResult messageResult = MessageResult.success();
        messageResult.setData(page);
        return messageResult;
    }

    /**
     * 个人所有广告
     *
     * @param shiroUser
     * @return
     */
    @RequestMapping(value = "all")
    public MessageResult allNormal(PageModel pageModel, AdvertiseControlStatus status, @SessionAttribute(API_HARD_ID_MEMBER) AuthMember shiroUser, HttpServletRequest request) {
    	Integer origin = 2;
    	// BooleanExpression eq = null;

        // if (status==null) {
        //eq = QAdvertise.advertise.member.id.eq(shiroUser.getId()).and(QAdvertise.advertise.status.eq(AdvertiseControlStatus.TURNOFF));
        // } else {
        //eq = QAdvertise.advertise.member.id.eq(shiroUser.getId()).and(QAdvertise.advertise.status.ne(AdvertiseControlStatus.TURNOFF));
        //  }

        Page<Advertise> all = advertiseService.pageQuery(pageModel.getPageNo(), pageModel.getPageSize(), origin,status, shiroUser.getId());
        // Page<Advertise> all = advertiseService.findAll(predicate, pageModel.getPageable());

        return success(all);
    }


    /**
     * 广告详情
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "adver/detail")
    public MessageResult detail(Long id, @SessionAttribute(API_HARD_ID_MEMBER) AuthMember shiroUser) {
        Advertise advertise = advertiseService.findOne(id);
        advertise.setMarketPrice(coins.get(advertise.getCoinUnit()));
        MessageResult result = MessageResult.success();
        result.setData(advertise);
        return result;
    }

    /**
     * 根据订单状态查询我的订单
     *
     * @param user
     * @param status   [ 0：已取消 1：未付款 2：已付款  3：已完成 4：申诉中  5: 进行中 (1、2、4) ]
     * @param pageNo
     * @param pageSize
     * @param orderSn
     * @return
     */
    @RequestMapping(value = "self")
    public MessageResult myOrder(@SessionAttribute(API_HARD_ID_MEMBER) AuthMember user, Integer status, int pageNo, int pageSize, String orderSn) {

        if (status != 0 && status != 1 && status != 2 && status != 3 && status != 4 && status != 5) {
            return MessageResult.error(500, "状态错误");
        }

        Page<Order> page;

        OrderStatus statusEnum = null;
        OrderStatus statusEnum_1 = null;
        OrderStatus statusEnum_2 = null;
        OrderStatus statusEnum_4 = null;
        if (status == 5) {
            for (OrderStatus s : OrderStatus.values()) {
                if (s.getOrdinal() == 1) {
                    statusEnum_1 = s;
                } else if (s.getOrdinal() == 2) {
                    statusEnum_2 = s;
                } else if (s.getOrdinal() == 4) {
                    statusEnum_4 = s;
                }
            }
            page = orderService.pageQueryApp(pageNo, pageSize, statusEnum_1, statusEnum_2, statusEnum_4, user.getId(), orderSn);
        } else {
            for (OrderStatus s : OrderStatus.values()) {
                if (s.getOrdinal() == status) {
                    statusEnum = s;
                    break;
                }
            }
            page = orderService.pageQuery(pageNo, pageSize, statusEnum, user.getId(), orderSn);
        }
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
                    scanOrder.setNickName(member.getNickName());
                }
            }
        }
        MessageResult result = MessageResult.success();
        result.setData(scanOrders);
        return result;
    }

    /**
     * 订单详情
     * 同时返回订单会员信息
     *
     * @param orderSn
     * @param user
     * @return
     */
    @RequestMapping(value = "order/detail")
    public MessageResult queryOrder(String orderSn, @SessionAttribute(API_HARD_ID_MEMBER) AuthMember user) {
        Order order = orderService.findOneByOrderSn(orderSn);
        notNull(order, msService.getMessage("ORDER_NOT_EXISTS"));
        MessageResult result = MessageResult.success();
        RespDetail respDetail = new RespDetail();

        Member member = memberService.findOne(order.getMemberId());
        Member memberCus = memberService.findOne(order.getCustomerId());
        respDetail.setPublisher(member);
        respDetail.setCustomer(memberCus);

        OrderDetail info = OrderDetail.builder().orderSn(orderSn)
                .unit(order.getCoin().getUnit())
                .status(order.getStatus())
                .amount(order.getNumber())
                .price(order.getPrice())
                .money(order.getMoney())
                .payTime(order.getPayTime())
                .createTime(order.getCreateTime())
                .timeLimit(order.getTimeLimit())
                .myId(user.getId()).memberMobile(member.getMobilePhone())
                .build();
        /*if (!order.getStatus().equals(OrderStatus.CANCELLED)) {*/
        PayInfo payInfo = PayInfo.builder()
                .bankInfo(order.getBankInfo())
                .alipay(order.getAlipay())
                .wechatPay(order.getWechatPay())
                .build();
        info.setPayInfo(payInfo);
        /* }*/
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
//        Member publisher = memberService.findByUsername(order.getMemberName());
//        Member customer = memberService.findByUsername(order.getCustomerName());
//        Member customer = memberService.findByUsername(order.getMemberName());
//        RespDetail respDetail = new RespDetail();
        respDetail.setOrderDetail(info);
//        respDetail.setPublisher(publisher);
//        respDetail.setCustomer(customer);
        result.setData(respDetail);
        return result;
    }

    /**
     * 获取会员信息
     *
     * @param name
     * @return
     */
    @RequestMapping(value = "member", method = RequestMethod.POST)
    public MessageResult memberAdvertises(String name) {
        Member member = memberService.findByUsername(name);
        if (member != null) {
            MemberAdvertiseInfo memberAdvertise = advertiseService.getMemberAdvertise(member, coins.getCoins());
            MessageResult result = MessageResult.success();
            result.setData(memberAdvertise);
            return result;
        } else {
            return MessageResult.error(msService.getMessage("MEMBER_NOT_EXISTS"));
        }
    }

}
