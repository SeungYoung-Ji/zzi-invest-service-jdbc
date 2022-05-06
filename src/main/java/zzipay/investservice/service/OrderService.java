package zzipay.investservice.service;

import zzipay.investservice.domain.Member;
import zzipay.investservice.domain.Order;
import zzipay.investservice.domain.OrderStatus;
import zzipay.investservice.domain.item.Item;
import zzipay.investservice.domain.item.Stock;
import zzipay.investservice.dto.UserOrderHistoryDto;
import zzipay.investservice.dto.UserOrderSummaryDto;
import zzipay.investservice.exception.CustomException;
import zzipay.investservice.exception.ExceptionEnum;
import zzipay.investservice.repository.ItemRepository;
import zzipay.investservice.repository.MemberRepository;
import zzipay.investservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zzipay.investservice.repository.StockRepository;

import javax.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final MemberRepository memberRepository;
    private final ItemRepository itemRepository;
    private final StockRepository stockRepository;

    @Transactional
    @Caching(evict = {@CacheEvict(value = "myOrder", key = "#memberId"),
            @CacheEvict(value = "myOrder", key = "#itemId")})
    public Order order(Long memberId, Long itemId, Long count) {
        
        Member member = memberRepository.findById(memberId).orElseThrow(
                () -> {
                    log.error("Cannot find member. MemberId = {}", memberId);
                    throw new CustomException(ExceptionEnum.NOT_FOUND_MEMBER);
                });
        Item item = itemRepository.findById(itemId).orElseThrow(
                () -> {
                    log.error("Cannot find item. ItemId = {}", itemId);
                    throw new CustomException(ExceptionEnum.NOT_FOUND_ORDER_PRODUCT);
                });

        item.validateTime();

        Order order = Order.builder()
                .member(member)
                .item(item)
                .count(count)
                .orderDate(LocalDateTime.now())
                .status(OrderStatus.ORDER)
                .build();

        // Redis 로 재고 관리 변경 예정
        updateStock(itemId, count);

        // query 가 나눠서 2번 나갈 것으로 예상?
        return orderRepository.save(order);
    }

    // DDD 다시 개발
    private void updateStock(Long itemId, Long count) {
        Stock stock = stockRepository.findById(itemId).orElseThrow(
                () -> {
                    throw new CustomException(ExceptionEnum.NOT_FOUND_ORDER_PRODUCT);
                });

        stockRepository.save(new Stock(itemId, stock.calculateRemainStock(count)));
    }

    @Cacheable(value = "myOrder", key = "#memberId")
    public List<UserOrderHistoryDto> findOrderHistory(Long memberId) {
        List<UserOrderHistoryDto> orderDtoList = new ArrayList<>();
        List<Order> orderList = orderRepository.findAllByMemberId(memberId);

        for (Order order : orderList) {
            if (order.getStatus() == OrderStatus.ORDER) {
                addOrderToDto(orderDtoList, order);
            }
        }

        return orderDtoList;
    }

    private void addOrderToDto(List<UserOrderHistoryDto> orderDtoList, Order order) {
        UserOrderHistoryDto orderDto = UserOrderHistoryDto.builder()
                .orderId(order.getId())
                .productId(order.getItem().getId())
                .title(order.getItem().getName())
                .totalInvestingAmount(order.calculateTotalInvestingAmount().getValue())
                .myInvestingAmount(order.calculateOrderInvestingAmount().getValue())
                .investingDate(order.getOrderDate())
                .build();
        orderDtoList.add(orderDto);
    }

    public List<UserOrderSummaryDto> findOrderSummary(Long memberId) {
        return orderRepository.findMemberOrderSummary(memberId);
    }


    /** 주문 취소 */
    @Transactional
    @CacheEvict(value = "myOrder", allEntries = true)
    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(
                () -> {
                    log.error("Illegal cancel detected. orderId = {}", orderId);
                    throw new CustomException(ExceptionEnum.NOT_FOUND_CANCEL_ORDER);
                });;
        verifyCancel(orderId, order);
        order.cancel(order.getItemId());
    }

    private void verifyCancel(Long orderId, Order order) {
        if (order.isCancel()) {
            log.warn("Already canceled order. orderId = {}", orderId);
            throw new CustomException(ExceptionEnum.ALREADY_CANCEL_ORDER);
        }
    }
}