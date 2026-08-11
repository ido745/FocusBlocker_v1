package com.focusapp.blocker.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DonationManager(context: Context) {

    sealed interface State {
        data object Loading : State
        data class Ready(val products: List<ProductDetails>) : State
        data object Success : State
        data object Unavailable : State
    }

    companion object {
        val PRODUCT_IDS = listOf("donation_tier_1", "donation_tier_2", "donation_tier_3")
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases?.forEach { consume(it) }
            }
        }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    reconcileOutstandingPurchases()
                    queryProducts()
                } else {
                    _state.value = State.Unavailable
                }
            }
            override fun onBillingServiceDisconnected() {
                _state.value = State.Unavailable
            }
        })
    }

    /**
     * Consumes anything Google says is bought but that we never finished handling.
     *
     * The purchase listener only fires while the app is alive. If the process is killed
     * between Google taking the money and us consuming — a crash, a swipe-away, the network
     * dropping — the purchase is left unconsumed and unacknowledged, and three things follow:
     * Google auto-refunds it after three days, the donor is told "you already own this item"
     * if they try again, and we quietly kept money for nothing. Querying on every connection
     * is what closes that window.
     */
    private fun reconcileOutstandingPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            // announce = false: finishing an old purchase must not pop "thank you" at
            // someone who just opened the dialog and has not donated yet.
            purchases.forEach { consume(it, announce = false) }
        }
    }

    private fun queryProducts() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(PRODUCT_IDS.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            })
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            _state.value = if (result.responseCode == BillingClient.BillingResponseCode.OK && details.isNotEmpty())
                State.Ready(details.sortedBy { it.oneTimePurchaseOfferDetails?.priceAmountMicros ?: 0L })
            else
                State.Unavailable
        }
    }

    fun launchPurchase(activity: Activity, product: ProductDetails) {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(product)
                    .build()
            ))
            .build()
        client.launchBillingFlow(activity, params)
    }

    private fun consume(purchase: Purchase, announce: Boolean = true) {
        // PENDING purchases (cash, slow payment methods) are not paid yet — Google delivers
        // them later through the listener, and consuming one now would be giving it away.
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        // Consuming both acknowledges the purchase (so Google does not auto-refund it after
        // three days) and makes the product buyable again, since donations are repeatable.
        client.consumeAsync(params) { _, _ ->
            if (announce) _state.value = State.Success
        }
    }

    fun disconnect() = client.endConnection()
}
