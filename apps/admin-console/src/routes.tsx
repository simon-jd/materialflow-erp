import { Navigate, type RouteObject } from "react-router";
import { AppLayout } from "./components/AppLayout";
import { NotFoundPage, RouteError } from "./components/RouteFallbacks";
import { ApprovalQueuePage } from "./features/approvals/ApprovalQueuePage";
import { InventoryPage } from "./features/inventory/InventoryPage";
import { CreateOrderPage } from "./features/orders/CreateOrderPage";
import { OrderDetailPage } from "./features/orders/OrderDetailPage";
import { OrderListPage } from "./features/orders/OrderListPage";

export const routes: RouteObject[] = [
  {
    element: <AppLayout />,
    errorElement: <RouteError />,
    children: [
      { index: true, element: <Navigate to="/orders" replace /> },
      { path: "orders", element: <OrderListPage /> },
      { path: "orders/new", element: <CreateOrderPage /> },
      { path: "orders/:orderId", element: <OrderDetailPage /> },
      { path: "approvals", element: <ApprovalQueuePage /> },
      { path: "inventory", element: <InventoryPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];
