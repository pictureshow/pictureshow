/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
#include <windows.h>
#include <appmodel.h>
#include <shobjidl.h>
#include <winrt/Windows.Data.Json.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Services.Store.h>
#include <chrono>
#include <thread>
#include <vector>

using namespace winrt;
using namespace Windows::Data::Json;
using namespace Windows::Services::Store;
#define API extern "C" __declspec(dllexport)
using Reply = void (*)(wchar_t const*);

API BOOL ps_windows_store_has_identity() {
    UINT32 length = 0;
    return GetCurrentPackageFullName(&length, nullptr) == ERROR_INSUFFICIENT_BUFFER;
}

template<class Async> auto await_store(Async const& operation) {
    // Store purchase UI needs an STA and window-message dispatch. This waits on
    // the dedicated WinRT thread, while the Compose window stays interactive.
    handle completed{CreateEventW(nullptr, TRUE, FALSE, nullptr)};
    if (!completed) throw_last_error();
    operation.Completed([event = completed.get()](auto&&, auto&&) noexcept { SetEvent(event); });
    HANDLE event = completed.get();
    DWORD index = 0;
    check_hresult(CoWaitForMultipleHandles(COWAIT_DISPATCH_CALLS | COWAIT_DISPATCH_WINDOW_MESSAGES,
        INFINITE, 1, &event, &index));
    return operation.GetResults();
}

static JsonObject store_request(int command, HWND owner, hstring const& token) {
    auto context = StoreContext::GetDefault();
    JsonObject reply;
    if (command == 2) {
        auto license = await_store(context.GetAppLicenseAsync());
        JsonArray entitlements;
        for (auto const& item : license.AddOnLicenses()) {
            auto value = item.Value();
            if (value.InAppOfferToken() != token) continue;
            JsonObject entitlement;
            entitlement.Insert(L"productId", JsonValue::CreateStringValue(token));
            entitlement.Insert(L"active", JsonValue::CreateBooleanValue(value.IsActive()));
            auto unixTime = clock::to_sys(value.ExpirationDate());
            entitlement.Insert(L"expirationMillis", JsonValue::CreateNumberValue(
                static_cast<double>(std::chrono::duration_cast<std::chrono::milliseconds>(unixTime.time_since_epoch()).count())));
            entitlements.Append(entitlement);
        }
        reply.Insert(L"entitlements", entitlements);
        return reply;
    }

    if (command == 1) {
        if (!IsWindow(owner)) throw hresult_invalid_argument(L"Store purchase needs a Pictureshow window.");
        check_hresult(context.as<IInitializeWithWindow>()->Initialize(owner));
    }
    auto tokens = single_threaded_vector<hstring>(std::vector<hstring>{token});
    auto query = await_store(context.GetAssociatedStoreProductsByInAppOfferTokenAsync(tokens));
    check_hresult(query.ExtendedError());
    JsonArray products;
    for (auto const& item : query.Products()) {
        auto product = item.Value();
        if (product.InAppOfferToken() != token) continue;
        for (auto const& sku : product.Skus()) {
            if (!sku.IsSubscription() || sku.IsTrial()) continue;
            auto term = sku.SubscriptionInfo();
            if (term.BillingPeriod() != 1 || term.BillingPeriodUnit() != StoreDurationUnit::Month) continue;
            if (command == 1) {
                auto purchase = await_store(sku.RequestPurchaseAsync());
                switch (purchase.Status()) {
                    case StorePurchaseStatus::Succeeded:
                    case StorePurchaseStatus::AlreadyPurchased:
                        reply.Insert(L"purchase", JsonValue::CreateStringValue(L"PURCHASED"));
                        return reply;
                    case StorePurchaseStatus::NotPurchased:
                        check_hresult(purchase.ExtendedError());
                        reply.Insert(L"purchase", JsonValue::CreateStringValue(L"CANCELLED"));
                        return reply;
                    default:
                        check_hresult(purchase.ExtendedError());
                        throw hresult_error(E_FAIL, L"The Microsoft Store could not complete the purchase.");
                }
            }
            JsonObject entry;
            entry.Insert(L"id", JsonValue::CreateStringValue(token));
            entry.Insert(L"name", JsonValue::CreateStringValue(product.Title()));
            auto price = sku.Price().FormattedRecurrencePrice();
            if (price.empty()) price = sku.Price().FormattedPrice();
            entry.Insert(L"displayPrice", JsonValue::CreateStringValue(price));
            products.Append(entry);
            reply.Insert(L"products", products);
            return reply;
        }
    }
    if (command == 1) throw hresult_error(E_FAIL, L"The monthly subscription is unavailable in the Microsoft Store.");
    reply.Insert(L"products", products);
    return reply;
}

API void ps_windows_store_request(int command, HWND owner, wchar_t const* token, Reply callback) {
    if (!ps_windows_store_has_identity()) {
        callback(L"{\"error\":\"Pictureshow has no Microsoft Store package identity.\"}");
        return;
    }
    try {
        std::thread worker([=] {
            try {
                init_apartment(apartment_type::single_threaded);
                struct Apartment { ~Apartment() { uninit_apartment(); } } apartment;
                try {
                    auto reply = store_request(command, owner, token).Stringify();
                    callback(reply.c_str());
                } catch (hresult_error const& error) {
                    JsonObject reply;
                    reply.Insert(L"error", JsonValue::CreateStringValue(error.message()));
                    callback(reply.Stringify().c_str());
                }
            } catch (...) {
                callback(L"{\"error\":\"Could not initialize the Microsoft Store connection.\"}");
            }
        });
        worker.join();
    } catch (...) {
        callback(L"{\"error\":\"Could not start the Microsoft Store connection.\"}");
    }
}
