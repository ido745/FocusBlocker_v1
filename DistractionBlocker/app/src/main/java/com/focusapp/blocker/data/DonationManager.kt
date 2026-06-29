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
                if (result.responseCode == BillingClient.BillingResponseCode.OK) queryProducts()
                else _state.value = State.Unavailable
            }
            override fun onBillingServiceDisconnected() {
                _state.value = State.Unavailable
            }
        })
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

    private fun consume(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        // Consuming makes the product purchasable again (donations are repeatable)
        client.consumeAsync(params) { _, _ -> _state.value = State.Success }
    }

    fun disconnect() = client.endConnection()
}
