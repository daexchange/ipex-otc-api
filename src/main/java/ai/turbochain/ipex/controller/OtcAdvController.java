package ai.turbochain.ipex.controller;

import ai.turbochain.ipex.coin.CoinExchangeFactory;
import ai.turbochain.ipex.constant.AdvertiseControlStatus;
import ai.turbochain.ipex.constant.AdvertiseType;
import ai.turbochain.ipex.constant.OrderStatus;
import ai.turbochain.ipex.constant.PageModel;
import ai.turbochain.ipex.entity.*;
import ai.turbochain.ipex.entity.transform.AuthMember;
import ai.turbochain.ipex.entity.transform.MemberAdvertiseInfo;
import ai.turbochain.ipex.entity.transform.ScanAdvertise;
import ai.turbochain.ipex.entity.transform.SpecialPage;
import ai.turbochain.ipex.model.screen.AdvertiseScreen;
import ai.turbochain.ipex.pagination.PageResult;
import ai.turbochain.ipex.service.*;
import ai.turbochain.ipex.util.MessageResult;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.sparkframework.sql.DataException;
import org.apache.catalina.servlet4preview.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static ai.turbochain.ipex.constant.SysConstant.API_HARD_ID_MEMBER;
import static ai.turbochain.ipex.constant.SysConstant.SESSION_MEMBER;
import static org.springframework.util.Assert.notNull;

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
    public MessageResult allNormal(PageModel pageModel, @SessionAttribute(API_HARD_ID_MEMBER) AuthMember shiroUser, HttpServletRequest request) {
        BooleanExpression eq = QAdvertise.advertise.member.id.eq(shiroUser.getId()).
                and(QAdvertise.advertise.status.ne(AdvertiseControlStatus.TURNOFF));
        if (request.getParameter("status") != null) {
            eq.and(QAdvertise.advertise.status.eq(AdvertiseControlStatus.valueOf(request.getParameter("status"))));
        }
        Page<Advertise> all = advertiseService.findAll(eq, pageModel.getPageable());
        return success(all);
    }

    /**
     * 个人所有广告
     *
     * @param
     * @return
     */
//    @RequestMapping(value = "self/all")
//    public MessageResult self(
//            AdvertiseScreen screen,
//            PageModel pageModel,
//            @SessionAttribute(SESSION_MEMBER) AuthMember shiroUser) {
//        //添加 指定用户条件
//        Predicate predicate = screen.getPredicate(QAdvertise.advertise.member.id.eq(shiroUser.getId()));
//        Page<Advertise> all = advertiseService.findAll(predicate, pageModel.getPageable());
//        return success(all);
//    }

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
    public MessageResult myOrder(OrderStatus status, int pageNo, int pageSize, String orderSn) {
        Page<Order> page = orderService.pageQuery(pageNo, pageSize, status, 88L, orderSn);
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
        Page<ScanOrder> scanOrders = page.map(x -> ScanOrder.toScanOrder(x, 88L));
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
     * 同时返回订单会员信息
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
        Member publisher = memberService.findByUsername(order.getMemberName());
        Member customer = memberService.findByUsername(order.getCustomerName());
        RespDetail respDetail = new RespDetail();
        respDetail.setOrderDetail(info);
        respDetail.setPublisher(publisher);
        respDetail.setCustomer(customer);
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
