package ai.turbochain.ipex.controller;


import static ai.turbochain.ipex.constant.PayMode.ALI;
import static ai.turbochain.ipex.constant.PayMode.BANK;
import static ai.turbochain.ipex.constant.PayMode.WECHAT;
import static ai.turbochain.ipex.constant.SysConstant.API_HARD_ID_MEMBER;
import static ai.turbochain.ipex.util.BigDecimalUtils.compare;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.validation.Valid;

import org.apache.catalina.servlet4preview.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.sparkframework.sql.DataException;

import ai.turbochain.ipex.coin.CoinExchangeFactory;
import ai.turbochain.ipex.constant.AdvertiseControlStatus;
import ai.turbochain.ipex.constant.AdvertiseLevel;
import ai.turbochain.ipex.constant.AdvertiseType;
import ai.turbochain.ipex.constant.MemberLevelEnum;
import ai.turbochain.ipex.constant.MemberRegisterOriginEnum;
import ai.turbochain.ipex.constant.PageModel;
import ai.turbochain.ipex.constant.PriceType;
import ai.turbochain.ipex.entity.Advertise;
import ai.turbochain.ipex.entity.Country;
import ai.turbochain.ipex.entity.Member;
import ai.turbochain.ipex.entity.MemberLegalCurrencyWallet;
import ai.turbochain.ipex.entity.OtcCoin;
import ai.turbochain.ipex.entity.QAdvertise;
import ai.turbochain.ipex.entity.transform.AuthMember;
import ai.turbochain.ipex.entity.transform.MemberAdvertiseDetail;
import ai.turbochain.ipex.entity.transform.MemberAdvertiseInfo;
import ai.turbochain.ipex.entity.transform.ScanAdvertise;
import ai.turbochain.ipex.entity.transform.Special;
import ai.turbochain.ipex.entity.transform.SpecialPage;
import ai.turbochain.ipex.exception.InformationExpiredException;
import ai.turbochain.ipex.model.screen.AdvertiseScreen;
import ai.turbochain.ipex.service.AdvertiseService;
import ai.turbochain.ipex.service.CountryService;
import ai.turbochain.ipex.service.LocaleMessageSourceService;
import ai.turbochain.ipex.service.MemberLegalCurrencyWalletService;
import ai.turbochain.ipex.service.MemberService;
import ai.turbochain.ipex.service.OtcCoinService;
import ai.turbochain.ipex.util.BigDecimalUtils;
import ai.turbochain.ipex.util.BindingResultUtil;
import ai.turbochain.ipex.util.Md5;
import ai.turbochain.ipex.util.MessageResult;
import lombok.extern.slf4j.Slf4j;


/**
 * HardId应用OTC广告管理
 * @author 
 * @date 2019年12月18日
 */
@RestController
@RequestMapping("/hard-id/advertise")
@Slf4j
public class HardIdAdvertiseController extends BaseController {

    @Autowired
    private AdvertiseService advertiseService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private OtcCoinService otcCoinService;
    @Autowired
    private MemberLegalCurrencyWalletService memberLegalCurrencyWalletService;
    @Autowired
    private CoinExchangeFactory coins;
    @Autowired
    private LocaleMessageSourceService msService;
    @Autowired
    private CountryService countryService;
    @Value("${spark.system.advertise:1}")
    private int allow;


    /**
     * 创建广告
     *
     * @param advertise 广告{@link Advertise}
     * @return
     */
    @RequestMapping(value = "create")
    @Transactional(rollbackFor = Exception.class)
    public MessageResult create(@Valid Advertise advertise, BindingResult bindingResult,
                                @SessionAttribute(API_HARD_ID_MEMBER) AuthMember user,
                                @RequestParam(value = "pay[]") String[] pay, String jyPassword) throws Exception {
        MessageResult result = BindingResultUtil.validate(bindingResult);
        if (result != null) {
            return result;
        }
        Assert.notEmpty(pay, msService.getMessage("MISSING_PAY"));
        Assert.hasText(jyPassword, msService.getMessage("MISSING_JYPASSWORD"));
        Member member1 = memberService.findOne(user.getId());
        Assert.isTrue(member1.getIdNumber() != null, msService.getMessage("NO_REALNAME"));
//        if (allow == 1) {
            //allow是1的时候，必须是认证商家才能发布广告
    //    Assert.isTrue(member1.getMemberLevel().equals(MemberLevelEnum.IDENTIFICATION), msService.getMessage("NO_BUSINESS"));
//        }
        String mbPassword = member1.getJyPassword();
        Assert.hasText(mbPassword, msService.getMessage("NO_SET_JYPASSWORD"));
        Assert.isTrue(Md5.md5Digest(jyPassword + member1.getSalt()).toLowerCase().equals(mbPassword), msService.getMessage("ERROR_JYPASSWORD"));
        AdvertiseType advertiseType = advertise.getAdvertiseType();
        StringBuffer payMode = checkPayMode(pay, advertiseType, member1);
        advertise.setPayMode(payMode.toString());
        OtcCoin otcCoin = otcCoinService.findOne(advertise.getCoin().getId());
       
        advertise.setPriceType(PriceType.REGULAR);//只允许固定价格
        if (otcCoin==null) {
            return MessageResult.error("币种不存在");
        }
        BigDecimal price = advertise.getPrice();
        if (price==null) {
            return MessageResult.error("请设置价格");
        }
        BigDecimal minLimit = advertise.getMinLimit();
        if (minLimit==null) {
            return MessageResult.error("请设置最低单笔交易额");
        } 
        BigDecimal maxLimit = advertise.getMaxLimit();
        if (maxLimit==null) {
            return MessageResult.error("请设置最高单笔交易额");
        }
        BigDecimal number = advertise.getNumber();
        if (number==null) {
            return MessageResult.error("请设置交易数量");
        }
        
        checkAmount(advertiseType, advertise, otcCoin, member1);
        advertise.setLevel(AdvertiseLevel.ORDINARY);
        advertise.setRemainAmount(advertise.getNumber());
        Member mb = new Member();
        mb.setId(user.getId());
        advertise.setMember(mb);
        advertise.setOrigin(MemberRegisterOriginEnum.HARDID.getSourceType());//代表来自应用HardId
        // 直接上架
        advertise.setStatus(AdvertiseControlStatus.PUT_ON_SHELVES);
        advertise.setTimeLimit(120000000);//2000000小时
        
        if (advertise.getAdvertiseType().equals(AdvertiseType.SELL)) {
        	MemberLegalCurrencyWallet memberLegalCurrencyWallet = memberLegalCurrencyWalletService.findByOtcCoinAndMemberId(otcCoin, user.getId());
            Assert.isTrue(compare(memberLegalCurrencyWallet.getBalance(), advertise.getNumber()), msService.getMessage("INSUFFICIENT_BALANCE"));
            Assert.isTrue(advertise.getNumber().compareTo(otcCoin.getSellMinAmount()) >= 0, msService.getMessage("SELL_NUMBER_MIN") + otcCoin.getSellMinAmount());
             
            result = memberLegalCurrencyWalletService.freezeBalance(memberLegalCurrencyWallet, advertise.getNumber());
            
            if (result.getCode() != 0) {
               // throw new InformationExpiredException("Information Expired");
                return MessageResult.error("余额不足，请先充币");
            }
        } else {
            Assert.isTrue(advertise.getNumber().compareTo(otcCoin.getBuyMinAmount()) >= 0, msService.getMessage("BUY_NUMBER_MIN") + otcCoin.getBuyMinAmount());
        }
        advertise.setRemainAmount(advertise.getNumber());
        advertise.setStatus(AdvertiseControlStatus.PUT_ON_SHELVES);
         
        Advertise ad = advertiseService.saveAdvertise(advertise);
        if (ad != null) {
            return MessageResult.success(msService.getMessage("CREATE_SUCCESS"));
        } else {
            return MessageResult.error(msService.getMessage("CREATE_FAILED"));
        }
    }

    
    /**
     * 我的广告详情
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "detail")
    public MessageResult detail(Long id, @SessionAttribute(API_HARD_ID_MEMBER) AuthMember shiroUser) {
    	if (id==null) {
            return MessageResult.error("广告ID不能为空");
        }
    	
    	MemberAdvertiseDetail advertise = advertiseService.findOne(id, shiroUser.getId());
        if (advertise==null) {
        	return MessageResult.error("当前会员下不存在该广告");
        }
    	advertise.setMarketPrice(coins.get(advertise.getCoinUnit()));
        MessageResult result = MessageResult.success();
        result.setData(advertise);
        return result;
    }
    
    
    /**
     * 广告下架
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "/off/shelves")
    @Transactional(rollbackFor = Exception.class)
    public MessageResult putOffShelves(Long id, @SessionAttribute(API_HARD_ID_MEMBER) AuthMember authMember) throws InformationExpiredException {
    	if (id==null) {
            return MessageResult.error("广告ID不能为空");
        }
    	
    	Advertise advertise = advertiseService.find(id, authMember.getId());
        Assert.isTrue(advertise != null, msService.getMessage("PUT_OFF_SHELVES_FAILED"));
        Assert.isTrue(advertise.getStatus().equals(AdvertiseControlStatus.PUT_ON_SHELVES), msService.getMessage("PUT_OFF_SHELVES_FAILED"));
        OtcCoin otcCoin = advertise.getCoin();
        if (advertise.getAdvertiseType().equals(AdvertiseType.SELL)) {
        	MemberLegalCurrencyWallet memberLegalCurrencyWallet = memberLegalCurrencyWalletService.findByOtcCoinAndMemberId(otcCoin, authMember.getId());
            MessageResult result = memberLegalCurrencyWalletService.thawBalance(memberLegalCurrencyWallet, advertise.getRemainAmount());
            if (result.getCode() != 0) {
                throw new InformationExpiredException("Information Expired");
            }
        }
        int ret = advertiseService.putOffShelves(advertise.getId(), advertise.getRemainAmount());
        if (!(ret > 0)) {
            throw new InformationExpiredException("Information Expired");
        }
       /* advertise.setNumber(BigDecimal.ZERO);
        advertise.setRemainAmount(BigDecimal.ZERO);
        advertise.setStatus(AdvertiseControlStatus.PUT_OFF_SHELVES);*/
        return MessageResult.success(msService.getMessage("PUT_OFF_SHELVES_SUCCESS"));
    }
    
    
    /**
     * 广告上架
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "/on/shelves")
    @Transactional(rollbackFor = Exception.class)
    public MessageResult putOnShelves(long id, @SessionAttribute(API_HARD_ID_MEMBER) AuthMember authMember) throws InformationExpiredException {
        Advertise advertise = advertiseService.find(id, authMember.getId());
        Assert.isTrue(advertise != null, msService.getMessage("PUT_ON_SHELVES_FAILED"));
        Assert.isTrue(advertise.getStatus().equals(AdvertiseControlStatus.PUT_OFF_SHELVES), msService.getMessage("PUT_ON_SHELVES_FAILED"));
        OtcCoin otcCoin = advertise.getCoin();
        if (advertise.getAdvertiseType().equals(AdvertiseType.SELL)) {
        	MemberLegalCurrencyWallet memberLegalCurrencyWallet = memberLegalCurrencyWalletService.findByOtcCoinAndMemberId(otcCoin, authMember.getId());
            Assert.isTrue(compare(memberLegalCurrencyWallet.getBalance(), advertise.getNumber()), msService.getMessage("INSUFFICIENT_BALANCE"));
            Assert.isTrue(advertise.getNumber().compareTo(otcCoin.getSellMinAmount()) >= 0, msService.getMessage("SELL_NUMBER_MIN") + otcCoin.getSellMinAmount());
            MessageResult result = memberLegalCurrencyWalletService.freezeBalance(memberLegalCurrencyWallet, advertise.getNumber());
            if (result.getCode() != 0) {
                throw new InformationExpiredException("Information Expired");
            }
        } else {
            Assert.isTrue(advertise.getNumber().compareTo(otcCoin.getBuyMinAmount()) >= 0, msService.getMessage("BUY_NUMBER_MIN") + otcCoin.getBuyMinAmount());
        }
        advertise.setRemainAmount(advertise.getNumber());
        advertise.setStatus(AdvertiseControlStatus.PUT_ON_SHELVES);
        return MessageResult.success(msService.getMessage("PUT_ON_SHELVES_SUCCESS"));
    }
    
    
    /**
     * 个人所有广告
     *
     * @param shiroUser
     * @return
     */
    @RequestMapping(value = "all")
    public MessageResult allNormal(
            PageModel pageModel,
            @SessionAttribute(API_HARD_ID_MEMBER) AuthMember shiroUser, HttpServletRequest request) {
        BooleanExpression eq = QAdvertise.advertise.member.id.eq(shiroUser.getId()).
                and(QAdvertise.advertise.status.ne(AdvertiseControlStatus.TURNOFF));;
        if(request.getParameter("status") != null){
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
    @RequestMapping(value = "self/all")
    public MessageResult self(
            AdvertiseScreen screen,
            PageModel pageModel,
            @SessionAttribute(API_HARD_ID_MEMBER) AuthMember shiroUser) {
        //添加 指定用户条件
        Predicate predicate = screen.getPredicate(QAdvertise.advertise.member.id.eq(shiroUser.getId()));
        Page<Advertise> all = advertiseService.findAll(predicate, pageModel.getPageable());
        return success(all);
    }



    /**
     * 修改广告
     *
     * @param advertise 广告{@link Advertise}
     * @return {@link MessageResult}
     */
    @RequestMapping(value = "update")
    @Transactional(rollbackFor = Exception.class)
    public MessageResult update(
            @Valid Advertise advertise,
            BindingResult bindingResult,
            @SessionAttribute(API_HARD_ID_MEMBER) AuthMember shiroUser,
            @RequestParam(value = "pay[]") String[] pay, String jyPassword) throws Exception {
        MessageResult result = BindingResultUtil.validate(bindingResult);
        if (result != null) {
            return result;
        }

        Assert.notEmpty(pay, msService.getMessage("MISSING_PAY"));
        Assert.notNull(advertise.getId(), msService.getMessage("UPDATE_FAILED"));
        Assert.hasText(jyPassword, msService.getMessage("MISSING_JYPASSWORD"));

        Member member = memberService.findOne(shiroUser.getId());

        Assert.isTrue(Md5.md5Digest(jyPassword + member.getSalt()).toLowerCase().equals(member.getJyPassword()), msService.getMessage("ERROR_JYPASSWORD"));
        AdvertiseType advertiseType = advertise.getAdvertiseType();

        StringBuffer payMode = checkPayMode(pay, advertiseType, member);

        advertise.setPayMode(payMode.toString());
        Advertise old = advertiseService.findOne(advertise.getId());
        Assert.notNull(old, msService.getMessage("UPDATE_FAILED"));
        Assert.isTrue(old.getStatus().equals(AdvertiseControlStatus.PUT_OFF_SHELVES), msService.getMessage("AFTER_OFF_SHELVES"));
        OtcCoin otcCoin = otcCoinService.findOne(old.getCoin().getId());
        checkAmount(old.getAdvertiseType(), advertise, otcCoin, member);
        Country country = countryService.findOne(advertise.getCountry().getZhName());
        old.setCountry(country);
        Advertise ad = advertiseService.modifyAdvertise(advertise, old);
        if (ad != null) {
            return MessageResult.success(msService.getMessage("UPDATE_SUCCESS"));
        } else {
            return MessageResult.error(msService.getMessage("UPDATE_FAILED"));
        }
    }
   


    /**
     * 删除广告
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "delete")
    @Transactional(rollbackFor = Exception.class)
    public MessageResult delete(Long id, @SessionAttribute(API_HARD_ID_MEMBER) AuthMember shiroUser) {
        Advertise advertise = advertiseService.find(id, shiroUser.getId());
        Assert.notNull(advertise, msService.getMessage("DELETE_ADVERTISE_FAILED"));
        Assert.isTrue(advertise.getStatus().equals(AdvertiseControlStatus.PUT_OFF_SHELVES), msService.getMessage("DELETE_AFTER_OFF_SHELVES"));
        advertise.setStatus(AdvertiseControlStatus.TURNOFF);
        return MessageResult.success(msService.getMessage("DELETE_ADVERTISE_SUCCESS"));
    }


    /**
     * 查询优质广告
     *
     * @return
     */
    @RequestMapping(value = "excellent")
    public MessageResult allExcellentAdvertise(AdvertiseType advertiseType) throws Exception {
        List<Map<String, String>> marketPrices = new ArrayList<>();
        List<Map<String, String>> otcCoins = otcCoinService.getAllNormalCoin();
        otcCoins.stream().forEachOrdered(x -> {
            Map<String, String> map = new HashMap<>(2);
            map.put("name", x.get("unit"));
            map.put("price", coins.get(x.get("unit")).toString());
            marketPrices.add(map);
        });
        List<ScanAdvertise> list = advertiseService.getAllExcellentAdvertise(advertiseType, marketPrices);
        MessageResult messageResult = MessageResult.success();
        messageResult.setData(list);
        return messageResult;
    }

    /**
     * 分页查询广告
     *
     * @param pageNo
     * @param pageSize
     * @return
     */
    @RequestMapping(value = "page")
    public MessageResult queryPageAdvertise(@RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
                                            Long id, AdvertiseType advertiseType,
                                            @RequestParam(value = "isCertified", defaultValue = "0") Integer isCertified) throws SQLException, DataException {
        OtcCoin otcCoin = otcCoinService.findOne(id);
        double marketPrice = coins.get(otcCoin.getUnit()).doubleValue();
        SpecialPage<ScanAdvertise> page = advertiseService.paginationAdvertise(pageNo, pageSize, otcCoin, advertiseType, marketPrice, isCertified);
        MessageResult messageResult = MessageResult.success();
        messageResult.setData(page);
        return messageResult;
    }

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

    private StringBuffer checkPayMode(String[] pay, AdvertiseType advertiseType, Member member) {
        StringBuffer payMode = new StringBuffer();
        Arrays.stream(pay).forEach(x -> {
            if (advertiseType.equals(AdvertiseType.SELL)) {
                if (ALI.getCnName().equals(x)) {
                    Assert.isTrue(member.getAlipay() != null, msService.getMessage("NO_ALI"));
                } else if (WECHAT.getCnName().equals(x)) {
                    Assert.isTrue(member.getWechatPay() != null, msService.getMessage("NO_WECHAT"));
                } else if (BANK.getCnName().equals(x)) {
                    Assert.isTrue(member.getBankInfo() != null, msService.getMessage("NO_BANK"));
                } else {
                    throw new IllegalArgumentException("pay parameter error");
                }
            }
            payMode.append(x + ",");
        });
        return payMode.deleteCharAt(payMode.length() - 1);
    }

    private void checkAmount(AdvertiseType advertiseType, Advertise advertise, OtcCoin otcCoin, Member member) {
        if (advertiseType.equals(AdvertiseType.SELL)) {
            Assert.isTrue(compare(advertise.getNumber(), otcCoin.getSellMinAmount()), msService.getMessage("SELL_NUMBER_MIN") + otcCoin.getSellMinAmount());
            MemberLegalCurrencyWallet memberLegalCurrencyWallet = memberLegalCurrencyWalletService.findByOtcCoinAndMemberId(otcCoin, member.getId());
            Assert.isTrue(compare(memberLegalCurrencyWallet.getBalance(), advertise.getNumber()), msService.getMessage("INSUFFICIENT_BALANCE"));
        } else {
            Assert.isTrue(compare(advertise.getNumber(), otcCoin.getBuyMinAmount()), msService.getMessage("BUY_NUMBER_MIN") + otcCoin.getBuyMinAmount());
        }
    }

    /**
     * 查询最新十条广告
     * TODO 查询提速
     */
    @RequestMapping(value = "newest")
    public MessageResult queryNewest() throws Exception {
        Special<ScanAdvertise> list = advertiseService.getLatestAdvertise();
        OtcCoin otcCoin;
        double finalPrice;

        //空指针
        if(list==null|| list.getContext() ==null) {
            return success("data null!");
        }

        for (ScanAdvertise adv : list.getContext()) {
            if(null != adv){
                otcCoin = otcCoinService.findOne(adv.getCoinId());
                if(null == otcCoin){
                    continue;
                }
                finalPrice = coins.get(otcCoin.getUnit()).doubleValue();
                if(null != adv.getPremiseRate()){
                    //pricetype = 0 ? price : 计算价格
                    adv.setPrice(BigDecimalUtils.round(((adv.getPremiseRate().doubleValue() + 100) / 100) * finalPrice,2));
                }
                adv.setUnit(otcCoin.getUnit());
                adv.setCoinName(otcCoin.getName());
                adv.setCoinNameCn(otcCoin.getNameCn());
            }
        }
        MessageResult messageResult = MessageResult.success();
        messageResult.setData(list);
        return messageResult;
    }
}
