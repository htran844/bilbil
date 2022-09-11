package vn.fs.controller;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.mail.MessagingException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.transaction.Transactional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.paypal.api.payments.Links;
import com.paypal.api.payments.Payment;
import com.paypal.base.rest.PayPalRESTException;
import com.stripe.Stripe;
import com.stripe.model.checkout.Session;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem;

import vn.fs.commom.CommomDataService;
import vn.fs.config.PaypalPaymentIntent;
import vn.fs.config.PaypalPaymentMethod;
import vn.fs.entities.CartItem;
import vn.fs.entities.Order;
import vn.fs.entities.OrderDetail;
import vn.fs.entities.Product;
import vn.fs.entities.User;
import vn.fs.repository.OrderDetailRepository;
import vn.fs.repository.OrderRepository;
import vn.fs.service.PaypalService;
import vn.fs.service.ShoppingCartService;
import vn.fs.util.Utils;

/**
 * @author DongTHD
 *
 */
@Controller
public class CartController extends CommomController {

	@Autowired
	HttpSession session;

	@Autowired
	CommomDataService commomDataService;

	@Autowired
	ShoppingCartService shoppingCartService;

	@Autowired
	private PaypalService paypalService;

	@Autowired
	OrderRepository orderRepository;

	@Autowired
	OrderDetailRepository orderDetailRepository;

	public Order orderFinal = new Order();
	public String payment_id = "";

	public static final String URL_PAYPAL_SUCCESS = "pay/success";
	public static final String URL_PAYPAL_CANCEL = "pay/cancel";
	
	public static final String URL_STRIPE_SUCCESS = "stripe/success";
	private Logger log = LoggerFactory.getLogger(getClass());

	@GetMapping(value = "/shoppingCart_checkout")
	public String shoppingCart(Model model) {

		Collection<CartItem> cartItems = shoppingCartService.getCartItems();
		model.addAttribute("cartItems", cartItems);
		model.addAttribute("total", shoppingCartService.getAmount());
		double totalPrice = 0;
		for (CartItem cartItem : cartItems) {
			double price = cartItem.getQuantity() * cartItem.getProduct().getPrice();
			totalPrice += price - (price * cartItem.getProduct().getDiscount() / 100);
		}

		model.addAttribute("totalPrice", totalPrice);
		model.addAttribute("totalCartItems", shoppingCartService.getCount());

		return "web/shoppingCart_checkout";
	}

	// add cartItem
	@GetMapping(value = "/addToCart")
	public String add(@RequestParam("productId") Long productId, HttpServletRequest request, Model model) {

		Product product = productRepository.findById(productId).orElse(null);

		session = request.getSession();
		Collection<CartItem> cartItems = shoppingCartService.getCartItems();
		if (product != null) {
			CartItem item = new CartItem();
			BeanUtils.copyProperties(product, item);
			item.setQuantity(1);
			item.setProduct(product);
			item.setId(productId);
			shoppingCartService.add(item);
		}
		session.setAttribute("cartItems", cartItems);
		model.addAttribute("totalCartItems", shoppingCartService.getCount());

		return "redirect:/products";
	}

	// delete cartItem
	@SuppressWarnings("unlikely-arg-type")
	@GetMapping(value = "/remove/{id}")
	public String remove(@PathVariable("id") Long id, HttpServletRequest request, Model model) {
		Product product = productRepository.findById(id).orElse(null);

		Collection<CartItem> cartItems = shoppingCartService.getCartItems();
		session = request.getSession();
		if (product != null) {
			CartItem item = new CartItem();
			BeanUtils.copyProperties(product, item);
			item.setProduct(product);
			item.setId(id);
			cartItems.remove(session);
			shoppingCartService.remove(item);
		}
		model.addAttribute("totalCartItems", shoppingCartService.getCount());
		return "redirect:/checkout";
	}

	// show check out
	@GetMapping(value = "/checkout")
	public String checkOut(Model model, User user) {

		Order order = new Order();
		model.addAttribute("order", order);

		Collection<CartItem> cartItems = shoppingCartService.getCartItems();
		model.addAttribute("cartItems", cartItems);
		model.addAttribute("total", shoppingCartService.getAmount());
		model.addAttribute("NoOfItems", shoppingCartService.getCount());
		double totalPrice = 0;
		for (CartItem cartItem : cartItems) {
			double price = cartItem.getQuantity() * cartItem.getProduct().getPrice();
			totalPrice += price - (price * cartItem.getProduct().getDiscount() / 100);
		}

		model.addAttribute("totalPrice", totalPrice);
		model.addAttribute("totalCartItems", shoppingCartService.getCount());
		commomDataService.commonData(model, user);

		return "web/shoppingCart_checkout";
	}
	
	@PostMapping("/checkout/update/{id}")
	public String updateCart(@PathVariable("id") Long id, 
			@RequestParam("quantity") Integer qty) {
		shoppingCartService.update(id, qty);
		return "redirect:/checkout";
	}

	// submit checkout
	@PostMapping(value = "/checkout")
	@Transactional
	public String checkedOut(Model model, Order order, HttpServletRequest request,HttpServletResponse response ,User user)
			throws MessagingException {

		Stripe.apiKey = "sk_test_51Ld455DLGYAXEWbbILljGw7iWYhzUYb8CaQI9Et9Vk6aKRn9PxggWsSTXMYhfbO4YnvUb5WlTVGVNoPSS2jioskI00e4DJZOcQ";
		
		String checkOut = request.getParameter("checkOut");

		Collection<CartItem> cartItems = shoppingCartService.getCartItems();

		double totalPrice = 0;
		for (CartItem cartItem : cartItems) {
			double price = cartItem.getQuantity() * cartItem.getProduct().getPrice();
			totalPrice += price - (price * cartItem.getProduct().getDiscount() / 100);
		}

		BeanUtils.copyProperties(order, orderFinal);
		if (StringUtils.equals(checkOut, "stripe")) {
			System.out.print("stripe");
			String cancelUrl = Utils.getBaseURL(request) + "/" + URL_PAYPAL_CANCEL;
			String successUrl = Utils.getBaseURL(request) + "/" + URL_STRIPE_SUCCESS;
			try {
				List<LineItem> elements = new ArrayList<LineItem>();
				for (CartItem cartItem : cartItems) {
					System.out.println((long) cartItem.getQuantity());
					System.out.println((long) cartItem.getProduct().getPrice());
					System.out.println(cartItem.getProduct().getProductName());
					double price = cartItem.getQuantity() * cartItem.getProduct().getPrice();
					elements.add(SessionCreateParams.LineItem.builder()
			            .setQuantity((long) cartItem.getQuantity())
			            .setPriceData(
			              SessionCreateParams.LineItem.PriceData.builder()
			                .setCurrency("vnd")
			                .setUnitAmount((long) cartItem.getProduct().getPrice())
			                .setProductData(
			                  SessionCreateParams.LineItem.PriceData.ProductData.builder()
			                    .setName(cartItem.getProduct().getProductName())
			                    .build())
			                .build())
			            .build());
				}
				SessionCreateParams params =
				        SessionCreateParams.builder()
				          .setMode(SessionCreateParams.Mode.PAYMENT)
				          .setSuccessUrl(successUrl)
				          .setCancelUrl(cancelUrl)
				          .addAllLineItem(elements)
				          .build();

				      Session session = Session.create(params);

				      System.out.print("payment_intent " + session.toString());
				      payment_id = session.getId();
//				      response.sendRedirect(session.getUrl());
//				      return "";
				return "redirect:" + session.getUrl();
			} catch (Exception e) {
				log.error(e.getMessage());
			}

		}
		

		session = request.getSession();
		Date date = new Date();
		order.setOrderDate(date);
		order.setStatus(0);
		order.getOrderId();
		order.setAmount(totalPrice);
		order.setUser(user);

		orderRepository.save(order);

		for (CartItem cartItem : cartItems) {
			OrderDetail orderDetail = new OrderDetail();
			orderDetail.setQuantity(cartItem.getQuantity());
			orderDetail.setOrder(order);
			orderDetail.setProduct(cartItem.getProduct());
			double unitPrice = cartItem.getProduct().getPrice();
			orderDetail.setPrice(unitPrice);
			orderDetailRepository.save(orderDetail);
		}

		// sendMail
		commomDataService.sendSimpleEmail(user.getEmail(), "Bilbil-Shop Xác Nhận Đơn hàng", "aaaa", cartItems,
				totalPrice, order);

		shoppingCartService.clear();
		session.removeAttribute("cartItems");
		model.addAttribute("orderId", order.getOrderId());

		return "redirect:/checkout_success";
	}

	// stripe
		@GetMapping(URL_STRIPE_SUCCESS)
		public String successPayStripe(
				HttpServletRequest request, User user, Model model) throws MessagingException {
			System.out.println(user.toString());
			System.out.println(model.toString());
			System.out.println(request.toString());
			System.out.println("payment_id"+ payment_id);
			Session session2;
			try {
				session2 = Session.retrieve(payment_id);
				System.out.println("payment_intent"+ session2.getPaymentIntent());
				Collection<CartItem> cartItems = shoppingCartService.getCartItems();
				model.addAttribute("cartItems", cartItems);
				model.addAttribute("total", shoppingCartService.getAmount());
				double totalPrice = 0;
				for (CartItem cartItem : cartItems) {
					double price = cartItem.getQuantity() * cartItem.getProduct().getPrice();
					totalPrice += price - (price * cartItem.getProduct().getDiscount() / 100);
				} 
				model.addAttribute("totalPrice", totalPrice);
				model.addAttribute("totalCartItems", shoppingCartService.getCount());
				try {
					session = request.getSession();
					Date date = new Date();
					orderFinal.setOrderDate(date);
					orderFinal.setStatus(2);
					orderFinal.getOrderId();
					orderFinal.setUser(user);
					orderFinal.setAmount(totalPrice);
					orderFinal.setPaymentintent(session2.getPaymentIntent());
					orderRepository.save(orderFinal);

					for (CartItem cartItem : cartItems) {
						OrderDetail orderDetail = new OrderDetail();
						orderDetail.setQuantity(cartItem.getQuantity());
						orderDetail.setOrder(orderFinal);
						orderDetail.setProduct(cartItem.getProduct());
						double unitPrice = cartItem.getProduct().getPrice();
						orderDetail.setPrice(unitPrice);
						orderDetailRepository.save(orderDetail);
					}

					// sendMail
					commomDataService.sendSimpleEmail(user.getEmail(), "Bilbil-Shop Xác Nhận Đơn hàng", "aaaa", cartItems,
							totalPrice, orderFinal);

					shoppingCartService.clear();
					session.removeAttribute("cartItems");
					model.addAttribute("orderId", orderFinal.getOrderId());
					orderFinal = new Order();
					return "redirect:/checkout_stripe_success";
				} catch (Exception e) {
					log.error(e.getMessage());
				}
			} catch (StripeException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			
			
			
			return "redirect:/";
		}

	
	// paypal
	@GetMapping(URL_PAYPAL_SUCCESS)
	public String successPay(@RequestParam("" + "" + "") String paymentId, @RequestParam("PayerID") String payerId,
			HttpServletRequest request, User user, Model model) throws MessagingException {
		Collection<CartItem> cartItems = shoppingCartService.getCartItems();
		model.addAttribute("cartItems", cartItems);
		model.addAttribute("total", shoppingCartService.getAmount());

		double totalPrice = 0;
		for (CartItem cartItem : cartItems) {
			double price = cartItem.getQuantity() * cartItem.getProduct().getPrice();
			totalPrice += price - (price * cartItem.getProduct().getDiscount() / 100);
		}
		model.addAttribute("totalPrice", totalPrice);
		model.addAttribute("totalCartItems", shoppingCartService.getCount());

		try {
			Payment payment = paypalService.executePayment(paymentId, payerId);
			if (payment.getState().equals("approved")) {

				session = request.getSession();
				Date date = new Date();
				orderFinal.setOrderDate(date);
				orderFinal.setStatus(2);
				orderFinal.getOrderId();
				orderFinal.setUser(user);
				orderFinal.setAmount(totalPrice);
				orderRepository.save(orderFinal);

				for (CartItem cartItem : cartItems) {
					OrderDetail orderDetail = new OrderDetail();
					orderDetail.setQuantity(cartItem.getQuantity());
					orderDetail.setOrder(orderFinal);
					orderDetail.setProduct(cartItem.getProduct());
					double unitPrice = cartItem.getProduct().getPrice();
					orderDetail.setPrice(unitPrice);
					orderDetailRepository.save(orderDetail);
				}

				// sendMail
				commomDataService.sendSimpleEmail(user.getEmail(), "Bilbil-Shop Xác Nhận Đơn hàng", "aaaa", cartItems,
						totalPrice, orderFinal);

				shoppingCartService.clear();
				session.removeAttribute("cartItems");
				model.addAttribute("orderId", orderFinal.getOrderId());
				orderFinal = new Order();
				return "redirect:/checkout_paypal_success";
			}
		} catch (PayPalRESTException e) {
			log.error(e.getMessage());
		}
		return "redirect:/";
	}

	// done checkout ship cod
	@GetMapping(value = "/checkout_success")
	public String checkoutSuccess(Model model, User user) {
		commomDataService.commonData(model, user);

		return "web/checkout_success";

	}

	// done checkout paypal
	@GetMapping(value = "/checkout_stripe_success")
	public String paypalSuccess(Model model, User user) {
		commomDataService.commonData(model, user);

		return "web/checkout_paypal_success";

	}

}
