package ai.turbochain.ipex.controller;

import ai.turbochain.ipex.coin.CoinExchangeFactory;
import ai.turbochain.ipex.constant.AdvertiseControlStatus;
import ai.turbochain.ipex.constant.AdvertiseType;
import ai.turbochain.ipex.constant.OrderStatus;
import ai.turbochain.ipex.constant.PageModel;
import ai.turbochain.ipex.entity.*;
import ai.turbochain.ipex.entity.transform.AuthMember;
import ai.turbochain.ipex.entity.transform.ScanAdvertise;
import ai.turbochain.ipex.entity.transform.SpecialPage;
import ai.turbochain.ipex.model.screen.AdvertiseScreen;
import ai.turbochain.ipex.pagination.PageResult;
import ai.turbochain.ipex.service.AdvertiseService;
import ai.turbochain.ipex.service.MemberService;
import ai.turbochain.ipex.service.OrderService;
import ai.turbochain.ipex.service.OtcCoinService;
import ai.turbochain.ipex.util.MessageResult;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.sparkframework.sql.DataException;
import org.apache.catalina.servlet4preview.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static ai.turbochain.ipex.constant.SysConstant.SESSION_MEMBER;

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
    private CoinExchangeFactory coins;

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
    public MessageResult allNormal(PageModel pageModel, HttpServletRequest request) {
        BooleanExpression eq = QAdvertise.advertise.member.id.eq(88L).
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

}
